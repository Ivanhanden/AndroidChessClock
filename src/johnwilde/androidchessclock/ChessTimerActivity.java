package johnwilde.androidchessclock;

import java.text.DecimalFormat;


import johnwilde.androidchessclock.TimerOptions.TimeControl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

/**
 * Activity holding two clocks and two buttons.
 */
public class ChessTimerActivity extends Activity {
	
	/**
	 * The 4 states are:
	 * 
	 * IDLE: 
	 *  	Waiting for a player to make the first move.
	 * 
	 * RUNNING:
	 * 		The timer for one player is running. 
	 * 
	 * PAUSED:
	 * 		Neither timer is running, but mActive stores
	 *      the button of the player whose timer will start
	 *      when play is resumed.
	 *      
	 * DONE:
	 *      Neither timer is running and one timer has reached
	 *      0.0.  mActive stores the player whose timer ran 
	 *      out.
	 *
	 */
	enum GameState {IDLE, RUNNING, PAUSED, DONE};
	GameState mCurrentState = GameState.IDLE;

	enum TimeControlType {BASIC, TOURNAMENT};
	TimeControlType mTimeControlType = TimeControlType.BASIC;
	
	enum DelayType {FISCHER, BRONSTEIN;}
	DelayType mDelayType;

	PlayerButton mButton1, mButton2;  	// The two big buttons
	Button mResetButton;				
	ToggleButton mPauseButton;
	
	// This field holds a reference to either mButton1 or mButton2.
	//
	// if mCurrentState == IDLE:
	//		it will be null.
	// if mCurrentState == RUNNING:
	//		it will point to the player whose clock is running
	// if mCurrentState == PAUSED:
	//		it will point to the player whose clock was running
	//		when paused
	// if mCurrentState == DONE:
	//		it will point to the player whose clock ran out of fime
	PlayerButton mActive; 
	
	private SharedPreferences mSharedPref;

	// The values below are populated from the user preferences
	int mInitialDurationSeconds = 60; 
	int mIncrementSeconds;
	boolean mAllowNegativeTime = false;
	boolean mShowMoveCounter = false;
	private boolean mWhiteOnLeft = false;
	private int mWakeLockType;
	// set when using TOURNAMENT time control
	private int mPhase1NumberMoves;
	private int mPhase2Minutes;
	
	// used to keep the screen bright during play
	private WakeLock mWakeLock;

	// Constants 
	private static final String TAG = "ChessTimerActivity";
	private static final int BUTTON_FADED = 25;
	private static final int BUTTON_VISIBLE = 255;
	private static final int REQUEST_CODE_PREFERENCES = 1;
	
	// Create all the objects and enter IDLE state
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// the layout looks best in landscape orientation
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		
		setContentView(R.layout.main);

		mButton1 = new PlayerButton( new Timer(R.id.whiteClock), R.id.whiteButton, R.id.whiteMoveCounter);
		mButton2 = new PlayerButton( new Timer(R.id.blackClock), R.id.blackButton, R.id.blackMoveCounter);

		mResetButton = (Button) findViewById(R.id.reset_button);
		mPauseButton = (ToggleButton) findViewById(R.id.pause_button);
		mPauseButton.setOnClickListener(new PauseButtonClickListener());
		
		mButton1.setButtonListener(new PlayerButtonClickListener(mButton1, mButton2));
		mButton2.setButtonListener(new PlayerButtonClickListener(mButton2, mButton1));

		mResetButton.setOnClickListener(new ResetButtonClickListener());
		mSharedPref = PreferenceManager.getDefaultSharedPreferences(this);
		
		// enable following line to clear settings if they are in a bad state
		//mSharedPref.edit().clear().apply();

		// set default values (for first run)
		PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
		loadAllUserPreferences();

		transitionTo(GameState.IDLE);
		
		acquireWakeLock();
		
		Log.d(TAG, "Finished onCreate()");
	}

    @Override
    public void onPause() {
    	releaseWakeLock();
    	super.onPause();
    }
    
    @Override
    public void onResume() {
	    acquireWakeLock();
	    super.onResume();
    }
    
    @Override
    public void onDestroy() {
    	releaseWakeLock();
    	super.onDestroy();
    }
	
	// Save data needed to recreate activity.  Enter PAUSED state
	// if we are currently RUNNING.
	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		
		if (mCurrentState == GameState.RUNNING)
			mPauseButton.performClick(); // pause, if not IDLE
		
		outState.putLong("Timer1", mButton1.timer.getMsToGo() );
		outState.putLong("Timer2", mButton2.timer.getMsToGo() );
		outState.putInt("MoveCounter1", mButton1.mMoveNumber );
		outState.putInt("MoveCounter2", mButton2.mMoveNumber );
		outState.putString("State", mCurrentState.toString());
		
		// if IDLE, the current state is NULL
		if (mCurrentState != GameState.IDLE)
			outState.putInt("ActiveButton", mActive.getButtonId()); 
	}
	
	// This is called after onCreate() and restores the activity state
	// using data saved in onSaveInstanceState().  The activity will
	// never be in the RUNNING state after this method.
	@Override
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);
		
		GameState stateToRestore = GameState.valueOf(savedInstanceState.getString("State"));
		
		// onCreate() puts us in IDLE and we don't need to do anything else
		if (stateToRestore == GameState.IDLE)
			return;
		
		long activeButtonId = savedInstanceState.getInt("ActiveButton");
		boolean button1Active = (mButton1.getButtonId() == activeButtonId ? true : false);
		boolean button2Active = (mButton2.getButtonId() == activeButtonId ? true : false);
		
		mButton1.setTimeAndState(savedInstanceState.getLong("Timer1"),
				savedInstanceState.getInt("MoveCounter1"), button1Active);
		mButton2.setTimeAndState(savedInstanceState.getLong("Timer2"), 
				savedInstanceState.getInt("MoveCounter2"), button2Active);

		if (stateToRestore == GameState.DONE){
			transitionTo(GameState.DONE);
			return;
		}
			
		if (stateToRestore == GameState.PAUSED){
			transitionToPauseAndShowDialog();
			return;
		}

	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		transitionToPauseAndToast();
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		switch (itemId) {
		case R.id.optionsmenu_preferences:
			launchPreferencesActivity();
			break;
		case R.id.optionsmenu_about:
			showAboutDialog();
			break;			
		// Generic catch all for all the other menu resources
		default:
			break;
		}
	
		return false;
	}

	// This method is called when the user preferences activity returns.  That
	// activity set fields in the Intent data to indicate what preferences
	// have been changed.  The method takes the action appropriate for what
	// has changed.  In some cases the clocks are reset.
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
	
		// check which activity has returned (for now we only have one, so
		// it isn't really necessary).
		if (requestCode == REQUEST_CODE_PREFERENCES) {
			
			if (data == null)
				return; // no preferences were changed
			
			// reset clocks using new settings
			if (data.getBooleanExtra(TimerOptions.TimerPref.TIME.toString(), false)){
				loadAllUserPreferences();
				transitionTo(GameState.IDLE);
				return; // exit early
			}

			if (data.getBooleanExtra(TimerOptions.TimerPref.NEGATIVE_TIME.toString(), false)){
				loadNegativeTimeUserPreference(TimerOptions.Key.NEGATIVE_TIME);
			}
			
			// set a new increment, if needed
			if (data.getBooleanExtra(TimerOptions.TimerPref.INCREMENT.toString(), false)){
				loadIncrementUserPreference(TimerOptions.Key.INCREMENT_SECONDS);
			}
			
			if (data.getBooleanExtra(TimerOptions.TimerPref.DELAY_TYPE.toString(), false)){
				loadDelayTypeUserPreference(TimerOptions.Key.DELAY_TYPE);
			}

			if (data.getBooleanExtra(TimerOptions.TimerPref.ADVANCED_TIME_CONTROL.toString(), false)){
				loadTimeControlPreferences();
				transitionTo(GameState.IDLE);
			}
			
			// create a new wakelock, if needed
			if (data.getBooleanExtra(TimerOptions.TimerPref.SCREEN.toString(), false)){
				loadScreenDimUserPreference();
				acquireWakeLock();
			}

			if (data.getBooleanExtra(TimerOptions.TimerPref.SHOW_MOVE_COUNTER.toString(), false)){
				loadMoveCounterUserPreference();
			}

			if (data.getBooleanExtra(TimerOptions.TimerPref.SWAP_SIDES.toString(), false)){
				loadSwapSidesUserPreference();
			}
		}
	}



	private void releaseWakeLock() {
    	if (mWakeLock != null){
	    	if ( mWakeLock.isHeld() ) {
	    		mWakeLock.release();
	    		Log.d(TAG, "released wake lock " + mWakeLock);
	    	}
    	}
	}
    
    private void acquireWakeLock() {
    	releaseWakeLock();
    	PowerManager pm = (PowerManager) getSystemService(ChessTimerActivity.POWER_SERVICE);  
    	mWakeLock = pm.newWakeLock(mWakeLockType, TAG);
    	mWakeLock.acquire();
    	Log.d(TAG, "acquired wake lock " + mWakeLock);
    }
	
	// All state transitions are occur here.  The logic that controls
	// the UI elements is here.
	public void transitionTo(GameState state){
		GameState start = mCurrentState;
		
		switch (state){
		case IDLE:
			mCurrentState = GameState.IDLE;
			mPauseButton.setClickable(false); // disable pause when IDLE
			mPauseButton.setChecked(false); // Changes text on Pause button
			mButton1.reset();
			mButton2.reset();
			break;
			
		case RUNNING:
			mCurrentState = GameState.RUNNING;
			mPauseButton.setClickable(true); // enable 'pause'
			mPauseButton.setChecked(false);
			
			// start the clock
			mActive.timer.start(0);
			break;
			
		case PAUSED:
			mCurrentState = GameState.PAUSED;
			mPauseButton.setChecked(true); // Changes text on Pause button
			mPauseButton.setClickable(true); // enable 'resume'
			// pause the clock
			mActive.timer.pause();
			break;
			
		case DONE:
			if (mActive != null){
				mCurrentState = GameState.DONE;
				mPauseButton.setClickable(false); // disable pause when DONE
				break;
			}
			else{
				Log.d(TAG, "Can't tranition to DONE when neither player is active");
				return;
			}
			
		}
		
		Log.d(TAG, "Transition from " + start + " to " + mCurrentState);
			
	}

	public void setActiveButtonAndMoveCount(PlayerButton button) {
		mActive = button;

		// Give visual indication of which player goes next by fading
		// the button of the player who just moved
		mActive.setTransparency(BUTTON_VISIBLE);
		PlayerButton other = (mButton1 == mActive ? mButton2 : mButton1);
		other.setTransparency(BUTTON_FADED);

		if (mShowMoveCounter){
			mActive.mMoveCounter.setVisibility(View.VISIBLE);
			String s = "Move " + mActive.mMoveNumber;
			mActive.mMoveCounter.setText(s);
			other.mMoveCounter.setVisibility(View.GONE);
		}
		else{
			mActive.mMoveCounter.setVisibility(View.GONE);
			other.mMoveCounter.setVisibility(View.GONE);
		}
		
		Log.d(TAG, "Setting active button = " + button.getButtonId() );
	}


	public void launchPreferencesActivity() {
		// launch an activity through this intent
		Intent launchPreferencesIntent = new Intent().setClass(this,
				TimerOptions.class);
		// Make it a subactivity so we know when it returns
		startActivityForResult(launchPreferencesIntent, 
				REQUEST_CODE_PREFERENCES);
	}

	public void showAboutDialog(){
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		String title = getString(R.string.app_name);
		String msg = getString(R.string.about_dialog);
		builder.setMessage(title + ", version: " + getPackageVersion() + "\n\n" + msg)
		       .setPositiveButton("OK", new DialogInterface.OnClickListener() {
		           public void onClick(DialogInterface dialog, int id) {
		                dialog.cancel();
		           }
		       });
		AlertDialog alert = builder.create();		
		alert.show();
	}
	
	public void showPauseDialog(){
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(R.string.pause_dialog)
		       .setPositiveButton("Resume", new DialogInterface.OnClickListener() {
		           public void onClick(DialogInterface dialog, int id) {
		                dialog.cancel();
		                mPauseButton.performClick();
		           }
		       });
		AlertDialog alert = builder.create();		
		alert.show();
	}

	private String getPackageVersion(){

		try {
			PackageInfo manager=getPackageManager().getPackageInfo(getPackageName(), 0);
			return manager.versionName;
		} catch (NameNotFoundException e) {
			return "Unknown";
		}
	}


	// Methods for loading USER PREFERENCES
	//
	// These methods are run after the user has changed
	// a preference and during onCreate().  The onActivityResult()
	// method is responsible for calling the method that matches
	// the preference that was changed.
	//
	// Note: the default values required by the SharedPreferences getXX 
	// methods are not used.  The SharedPreferences will have  their default 
	// values set (in onCreate() ) and those defaults are saved in preferences.xml
	
	private void loadAllUserPreferences() {
		loadTimeControlPreferences();
		loadMoveCounterUserPreference();
		loadScreenDimUserPreference();
	}

	// determine whether we're using BASIC or TOURNAMENT time control
	private void loadTimeControlPreferences() {
		TimerOptions.TimeControl timeControl = TimerOptions.TimeControl.valueOf(
				mSharedPref.getString(TimerOptions.Key.TIMECONTROL_TYPE.toString(), "DISABLED")
				);
		
		if (timeControl == TimeControl.DISABLED){
			mTimeControlType = TimeControlType.BASIC;
			loadBasicTimeControlUserPreference();
		}else{
			mTimeControlType = TimeControlType.TOURNAMENT;
			loadAdvancedTimeControlUserPreference();
		}
	}

	private void loadBasicTimeControlUserPreference(){
		loadInitialTimeUserPreferences();
		loadIncrementUserPreference(TimerOptions.Key.INCREMENT_SECONDS);
		loadDelayTypeUserPreference(TimerOptions.Key.DELAY_TYPE);
		loadNegativeTimeUserPreference(TimerOptions.Key.NEGATIVE_TIME);
	}

    private void loadAdvancedTimeControlUserPreference() {
    	
		int minutes1 = Integer.parseInt(mSharedPref.getString(
				TimerOptions.Key.FIDE_MIN_PHASE1.toString(), "0"));
    	
		setInitialDuration( minutes1 * 60 );
		
		mPhase1NumberMoves = Integer.parseInt(mSharedPref.getString(
				TimerOptions.Key.FIDE_MOVES_PHASE1.toString(), "0"));

		mPhase2Minutes = Integer.parseInt(mSharedPref.getString(
				TimerOptions.Key.FIDE_MIN_PHASE2.toString(), "0"));

		loadDelayTypeUserPreference(TimerOptions.Key.ADV_DELAY_TYPE);
		loadIncrementUserPreference(TimerOptions.Key.ADV_INCREMENT_SECONDS);
		loadNegativeTimeUserPreference( TimerOptions.Key.ADV_NEGATIVE_TIME );
	}

	private void loadMoveCounterUserPreference() {
 		mShowMoveCounter = mSharedPref.getBoolean(TimerOptions.Key.SHOW_MOVE_COUNTER.toString(), false);
		if (mCurrentState == GameState.PAUSED)
			setActiveButtonAndMoveCount(mActive);
	}
	private void loadSwapSidesUserPreference() {
 		mWhiteOnLeft = mSharedPref.getBoolean(TimerOptions.Key.SWAP_SIDES.toString(), false);
 		configureSides();
	}	
	private void configureSides() {
		View whiteClock = findViewById(R.id.whiteClock);
		View blackClock = findViewById(R.id.blackClock);

		View whiteButton = findViewById(R.id.whiteButton);
		View blackButton = findViewById(R.id.blackButton);

		View whiteMoveCounter = findViewById(R.id.whiteMoveCounter);
		View blackMoveCounter = findViewById(R.id.blackMoveCounter);
		
		LinearLayout leftClockContainer = (LinearLayout)findViewById(R.id.leftClockContainer);
		LinearLayout rightClockContainer = (LinearLayout)findViewById(R.id.rightClockContainer);
		leftClockContainer.removeAllViewsInLayout();
		rightClockContainer.removeAllViewsInLayout();	
		
		FrameLayout leftButtonContainer = (FrameLayout)findViewById(R.id.frameLayoutLeft);
		FrameLayout rightButtonContainer = (FrameLayout)findViewById(R.id.frameLayoutRight);
		leftButtonContainer.removeAllViewsInLayout();
		rightButtonContainer.removeAllViewsInLayout();	
		

		if (mWhiteOnLeft) {
			leftClockContainer.addView(whiteClock);
			rightClockContainer.addView(blackClock);
			leftButtonContainer.addView(whiteButton);
			leftButtonContainer.addView(whiteMoveCounter);
			rightButtonContainer.addView(blackButton);
			rightButtonContainer.addView(blackMoveCounter);
		} else 
		{
			leftClockContainer.addView(blackClock);
			rightClockContainer.addView(whiteClock);
			leftButtonContainer.addView(blackButton);
			leftButtonContainer.addView(blackMoveCounter);
			rightButtonContainer.addView(whiteButton);
			rightButtonContainer.addView(whiteMoveCounter);
		}
		
	}

	private void loadNegativeTimeUserPreference(TimerOptions.Key key) {
		mAllowNegativeTime = mSharedPref.getBoolean(key.toString(), false);
	}
	
	private void loadDelayTypeUserPreference(TimerOptions.Key key){
		String[] delayTypes = getResources().getStringArray(R.array.delay_type_values);
		String delayTypeString = mSharedPref.getString(key.toString(), delayTypes[0]);

		mDelayType = DelayType.valueOf(delayTypeString.toUpperCase());
	}

	private void loadScreenDimUserPreference() {
		boolean allowScreenToDim = mSharedPref.getBoolean(TimerOptions.Key.SCREEN_DIM.toString(), true);
    	/** Create a PowerManager object so we can get the wakelock */
    	mWakeLockType = allowScreenToDim ? PowerManager.SCREEN_DIM_WAKE_LOCK:
    				       PowerManager.SCREEN_BRIGHT_WAKE_LOCK;
	}

	private void loadIncrementUserPreference(TimerOptions.Key key) {
		int seconds = Integer.parseInt(mSharedPref.getString(key.toString(), "0"));
		setIncrement(seconds);
	}

	private void loadInitialTimeUserPreferences() {
		
		int minutes = Integer.parseInt(mSharedPref.getString(
				TimerOptions.Key.MINUTES.toString(), "0"));
		int seconds = Integer.parseInt(mSharedPref.getString(
				TimerOptions.Key.SECONDS.toString(), "0"));
		
		setInitialDuration(minutes * 60 + seconds);
	}

	private void setInitialDuration(int seconds) {
		mInitialDurationSeconds = seconds;
	}
	private void setIncrement(int seconds) {
		mIncrementSeconds = seconds;
	}

	// Class to aggregate a button, a timer and a move counter.
	// It provides a method for setting the time which is used when the 
	// activity must be recreated after it had been started.
	// The methods of this class implement the logic of when time
	// should be added to each clock according to the style of time
	// control that the user configured.
	class PlayerButton{
		Timer timer;
		ImageButton button;
		TextView mMoveCounter;
		boolean isFaded = false;
		private int mId;
		private int mMoveNumber;
		
		PlayerButton(Timer timer, int buttonId, int moveCounterId){
			this.timer = timer;
			button = (ImageButton)findViewById(buttonId);
			mMoveCounter = (TextView)findViewById(moveCounterId);
			mId = buttonId;
			mMoveNumber = 1;
		}		
		
		public int getButtonId(){
			return mId;
		}
		
		void setButtonListener(PlayerButtonClickListener listener){
			button.setOnClickListener(listener);
		}

		// 0 is fully transparent, 255 is fully opaque
		private void setTransparency(int alpha) {
			if (button == null){
				Log.e(TAG, "Button is NULL");
			}
			
			button.getDrawable().setAlpha(alpha);
			button.invalidateDrawable(button.getDrawable());
		}
		

		public void setTimeAndState(long time, int moveCount, boolean isActive){
			if (isActive)
				setActiveButtonAndMoveCount(this);
			mMoveNumber = moveCount;
			timer.initializeWithValue(time);
		}
		
		// Put the button into the initial 'IDLE' configuration
		public void reset(){
			mMoveNumber = 1;
			timer.reset();
			setTransparency(BUTTON_VISIBLE);
			mMoveCounter.setVisibility(View.GONE);
		}

		public void moveFinished() {
			mMoveNumber++;
			
			if ( mTimeControlType == TimeControlType.TOURNAMENT ){
				if ( mMoveNumber == (mPhase1NumberMoves + 1) ){
					timer.increment(mPhase2Minutes * 60);
				}
			}
			
			timer.pause();

			if (mDelayType == DelayType.FISCHER)
				timer.increment(mIncrementSeconds);
			
		}

		public void moveStarted() {
			
			if (mDelayType == DelayType.BRONSTEIN)
				timer.start(mIncrementSeconds*1000);
			else
				timer.start(0);
		}
	}
	
	/**
	 * Pause one clock and start the other when a button is clicked.
	 */
	final class PlayerButtonClickListener implements OnClickListener {
		PlayerButton mine, other;
		
		public PlayerButtonClickListener(PlayerButton mine, PlayerButton other) {
			this.mine = mine;
			this.other = other;
		}

		@Override
		public void onClick(View v) {

			switch (mCurrentState){

			case PAUSED: 
				// alternate way to un-pause the activity
				// the primary way is to click the "Pause-Reset" toggle button
				mPauseButton.performClick();
				return;

			case DONE: // do nothing
				return;
				
			case RUNNING:
				if (mine.timer.isRunning()) {
					mine.moveFinished();
					other.moveStarted();
					setActiveButtonAndMoveCount(other);
				}
				break;

			case IDLE:
				// the game just started 
				setActiveButtonAndMoveCount(other);
				transitionTo(GameState.RUNNING);
				break;
			}
		}
	}

	/**
	 * Reset the clocks.
	 */
	final class ResetButtonClickListener implements OnClickListener {
		@Override
		public void onClick(View v) {
			loadAllUserPreferences();
			transitionTo(GameState.IDLE);
		}
	}

	/**
	 * Pause the clock that is running.
	 */
	final class PauseButtonClickListener implements OnClickListener {
		@Override
		public void onClick(View v) {
			
			if (mCurrentState == GameState.DONE ||
				mCurrentState == GameState.IDLE)
				return;
			
			if ( mCurrentState == GameState.PAUSED ){
				transitionTo(GameState.RUNNING);
			}
			else{
				transitionToPauseAndShowDialog();
			}
		}
	}

	public void transitionToPauseAndShowDialog(){
		transitionTo(GameState.PAUSED);
		showPauseDialog();
	}
	
	public void transitionToPauseAndToast(){
		if (mCurrentState == GameState.DONE ||
				mCurrentState == GameState.IDLE)
				return;
		transitionTo(GameState.PAUSED);
		Toast.makeText(this, "Paused. Press to resume.", Toast.LENGTH_SHORT).show();
	}
	
	// This class updates each player's clock.
	final class Timer implements OnLongClickListener {
		TextView mView;
		long mMillisUntilFinished;
		InnerTimer mCountDownTimer;
		boolean isRunning = false;

		// formatters for displaying text in timer
		DecimalFormat dfOneDecimal = new DecimalFormat("0.0");
		DecimalFormat dfOneDigit = new DecimalFormat("0");
		DecimalFormat dfTwoDigit = new DecimalFormat("00");

		Timer(int id) {
			mView = (TextView) findViewById(id);
			initialize();
		}

		public void initializeWithValue(long msToGo) {
			mView.setLongClickable(true);
			mView.setOnLongClickListener(this);
			mMillisUntilFinished = msToGo;
			mCountDownTimer = new InnerTimer();
			isRunning = false;
			mView.setTextColor( Color.BLACK );
			
			updateTimerText();			
		}

		
		public void initialize() {
			initializeWithValue(mInitialDurationSeconds * 1000);
			if (mDelayType ==  DelayType.FISCHER)
				increment(mIncrementSeconds);
		}

		public void increment(int mIncrementSeconds) {
			mMillisUntilFinished += mIncrementSeconds * 1000;
			updateTimerText();
		}

		public boolean isRunning() {
			return isRunning;
		}

		public void start(int delayMillis) {
			mCountDownTimer.startAfterDelay(delayMillis);
			isRunning = true;
		}

		public void pause() {
			if (mCountDownTimer != null)
				mCountDownTimer.pause();
			isRunning = false;
		}

		public void reset() {
			mCountDownTimer.cancel();
			initialize();
		}

		public void updateTimerText() {
			if (getMsToGo() < 10000)
				mView.setTextColor(Color.RED);
			else
				mView.setTextColor(Color.BLACK);
			
			mView.setText(formatTime(mMillisUntilFinished));
		}

		private String formatTime(long millisIn) {
			// 1000 ms in 1 second
			// 60*1000 ms in 1 minute
			// 60*60*1000 ms in 1 hour

			String stringSec, stringMin, stringHr;
			long millis = Math.abs(millisIn);
			
			// Parse the input (in ms) into integer hour, minute, and second
			// values
			long hours = millis / (1000 * 60 * 60);
			millis -= hours * (1000 * 60 * 60);

			long min = millis / (1000 * 60);
			millis -= min * (1000 * 60);

			long sec = millis / 1000;
			millis -= sec * 1000;

			// Construct string
			if (hours > 0)
				stringHr = dfOneDigit.format(hours) + ":";
			else
				stringHr = "";

			if (hours > 0 )
				stringMin = dfTwoDigit.format(min) + ":";
			else if ( min > 0)
				stringMin = dfOneDigit.format(min) + ":";
			else	
				stringMin = "";

			stringSec = dfTwoDigit.format(sec);  

			if (hours==0 && min==0){
				// Desired behavior:
				// 
				// for 0 <= millisIn < 10000 (between 0 and 10 seconds)
				//   clock should read like: "N.N"
				// for -999 <= millisIn <= -1 (the second after passing 0.0)
				//   clock should read "0"
				// for millisIn < -999 (all time less than -1 seconds)
				//   clock should read like : "-N"
				
				// modify formatting when less than 10 seconds
				if (sec < 10 && millisIn >= 0) // between 0 and 9 seconds
					stringSec = dfOneDecimal.format((double) sec
							+ (double) millis / 1000.0);
				else if (sec < 10 && millisIn < 0) //  between -1 and -9
					stringSec = dfOneDigit.format((double) sec
							+ (double) millis / 1000.0);
			}

			// clock is <= -1 second, prepend a minus sign
			if (millisIn <= -1000){
				return "-" + stringHr + stringMin + stringSec;
			}
			
			return stringHr + stringMin + stringSec;

		}

		long getMsToGo(){
			return mMillisUntilFinished;
		}
		
		boolean getAllowNegativeTime(){
			return mAllowNegativeTime;
		}

		public void done() {
			mView.setText("0.0");
			mView.setTextColor(Color.RED);
			transitionTo(GameState.DONE);
		}

		class InnerTimer {
			long mLastUpdateTime = 0L;
			Handler mHandler = new Handler();
			
			// this class will update itself (and call
			// updateTimerText) accordingly:
			//     if getMsToGo() > 10 * 1000, every 1000 ms
			// 	   if getMsToGo() < 10 * 1000, every 100 ms
			//     if getMsToGo() < 0 and getAllowNegativeTime is true, every 1000 ms
			Runnable mUpdateTimeTask = new Runnable(){
				public void run(){
					long ellapsedTime = SystemClock.uptimeMillis() - mLastUpdateTime;
					mMillisUntilFinished -= ellapsedTime;
					mLastUpdateTime = SystemClock.uptimeMillis();
					
					if (getMsToGo() > 10000){
						updateTimerText();
						mHandler.postDelayed(mUpdateTimeTask, 1000);
					}
					else if (getMsToGo() < 10000 && getMsToGo() > 0){
						updateTimerText();
						mHandler.postDelayed(mUpdateTimeTask, 100);
					}
					else if (getMsToGo() < 0 && getAllowNegativeTime()){
						updateTimerText();
						mHandler.postDelayed(mUpdateTimeTask, 1000);
					}
					else{
						mHandler.removeCallbacks(mUpdateTimeTask);
						done();
						return;
					}
				}
			};
						
			void startAfterDelay(int delayMillis){
				mLastUpdateTime = SystemClock.uptimeMillis() + delayMillis;
				mHandler.postDelayed(mUpdateTimeTask, delayMillis);
			}

			void pause() {
				mHandler.removeCallbacks(mUpdateTimeTask);
				// if called from onRestoreInstanceState(), mLastUpdateTime == 0 because
				// its value is not persisted.  No need to do anything else.
				if (mLastUpdateTime != 0L){
					// account for the time that has elapsed since our last update and pause the clock
					// if using BRONSTEIN this may be negative, so check for that case.
					long msSinceLastUpdate = ( SystemClock.uptimeMillis() - mLastUpdateTime );
					if (msSinceLastUpdate > 0)
						mMillisUntilFinished -= msSinceLastUpdate;
				}
			}

			void cancel() {
				mHandler.removeCallbacks(mUpdateTimeTask);
			}

		}

		@Override
		public boolean onLongClick(View v) {
			// launch activity that allows user to set time and increment values
			transitionToPauseAndToast();
			launchPreferencesActivity();
			return true;
		}

		public View getView() {
			return mView;
		}

	}
	
}