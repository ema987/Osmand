package net.osmand.plus.voice;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.speech.tts.TextToSpeech.OnUtteranceCompletedListener;
import android.support.v7.app.AlertDialog;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.SettingsActivity;
import net.osmand.plus.routing.VoiceRouter;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public class TTSCommandPlayerImpl extends AbstractPrologCommandPlayer {
	public final static String PEBBLE_ALERT = "PEBBLE_ALERT";
	public final static String WEAR_ALERT = "WEAR_ALERT";
	private static final class IntentStarter implements
			DialogInterface.OnClickListener {
		private final Context ctx;
		private final String intentAction;
		private final Uri intentData;

		private IntentStarter(Context ctx, String intentAction) {
			this(ctx,intentAction, null);
		}

		private IntentStarter(Context ctx, String intentAction, Uri intentData) {
			this.ctx = ctx;
			this.intentAction = intentAction;
			this.intentData = intentData;
		}

		@Override
		public void onClick(DialogInterface dialog, int which) {
			Intent installIntent = new Intent();
			installIntent.setAction(intentAction);
			if (intentData != null) {
				installIntent.setData(intentData);
			}
			ctx.startActivity(installIntent);
		}
	}

	private static final String CONFIG_FILE = "_ttsconfig.p";
	private static final int[] TTS_VOICE_VERSION = new int[] { 102, 103 }; // !! MUST BE SORTED  
	// No more TTS v101 support because of too many changes
	// TODO: We could actually remove v102 support, I am done updating all existing 35 TTS voices to v103. Hardy, July 2016
	private static final Log log = PlatformUtil.getLog(TTSCommandPlayerImpl.class);
	private static TextToSpeech mTts;
	private static String ttsVoiceName = "";
	private Context mTtsContext;
	private HashMap<String, String> params = new HashMap<String, String>();
	private VoiceRouter vrt;

	public TTSCommandPlayerImpl(Activity ctx, VoiceRouter vrt, String voiceProvider)
			throws CommandPlayerException {
		super((OsmandApplication) ctx.getApplicationContext(), voiceProvider, CONFIG_FILE, TTS_VOICE_VERSION);
		this.vrt = vrt;
		if (Algorithms.isEmpty(language)) {
			throw new CommandPlayerException(
					ctx.getString(R.string.voice_data_corrupted));
		}
		OsmandApplication app = (OsmandApplication) ctx.getApplicationContext();
		if(app.accessibilityEnabled()) {
			cSpeechRate = app.getSettings().SPEECH_RATE.get();
		}
		initializeEngine(app, ctx);
		params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, app.getSettings().AUDIO_STREAM_GUIDANCE.get().toString());
	}
	
	

	/**
	 * Since TTS requests are asynchronous, playCommands() can be called before
	 * the TTS engine is done. We use this field to keep track of concurrent tts
	 * activity. Where tts activity is defined as the time between tts.speak()
	 * and the call back to onUtteranceCompletedListener().  This allows us to
	 * optimize use of requesting and abandoning audio focus.
	 */
	private static int ttsRequests;
	private float cSpeechRate = 1;
	private boolean speechAllowed = false;
	
	// Called from the calculating route thread.
	@Override
	public synchronized void playCommands(CommandBuilder builder) {
		final List<String> execute = builder.execute(); //list of strings, the speech text, play it
		StringBuilder bld = new StringBuilder();
		for (String s : execute) {
			bld.append(s).append(' ');
		}
		sendAlertToPebble(bld.toString());
		if (mTts != null && !vrt.isMute() && speechAllowed) {
			if (ttsRequests++ == 0) {
				requestAudioFocus();
				// Delay first prompt of each batch to allow BT SCO connection being established
				if (ctx.getSettings().AUDIO_STREAM_GUIDANCE.get() == 0) {
					ttsRequests++;
					if (android.os.Build.VERSION.SDK_INT < 21) {
						params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,""+System.currentTimeMillis());
						mTts.playSilence(ctx.getSettings().BT_SCO_DELAY.get(), TextToSpeech.QUEUE_ADD, params);
					} else {
						mTts.playSilentUtterance(ctx.getSettings().BT_SCO_DELAY.get(), TextToSpeech.QUEUE_ADD, ""+System.currentTimeMillis());
					}
				}
			}
			log.debug("ttsRequests="+ttsRequests);
			params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,""+System.currentTimeMillis());
			mTts.speak(bld.toString(), TextToSpeech.QUEUE_ADD, params);
			// Audio focus will be released when onUtteranceCompleted() completed is called by the TTS engine.
		} else if (ctx != null && vrt.isMute()) {
			sendAlertToAndroidWear(ctx, bld.toString());
		}
	}

	@Override
	public void stop(){
		ttsRequests = 0;
		if (mTts != null){
			mTts.stop();
		}
		abandonAudioFocus();
	}

	public void sendAlertToPebble(String bld) {
		final Intent i = new Intent("com.getpebble.action.SEND_NOTIFICATION");
		final Map<String, Object> data = new HashMap<String, Object>();
		data.put("title", "Voice");
		data.put("body", bld.toString());
		final JSONObject jsonData = new JSONObject(data);
		final String notificationData = new JSONArray().put(jsonData).toString();
		i.putExtra("messageType", PEBBLE_ALERT);
		i.putExtra("sender", "OsmAnd");
		i.putExtra("notificationData", notificationData);
		if (ctx != null) {
			ctx.sendBroadcast(i);
			log.info("Send message to pebble " + bld.toString());
		}
	}


	private void initializeEngine(final Context ctx, final Activity act) {
		if (mTtsContext != ctx) {
			internalClear();
		}
		if (mTts == null) {
			mTtsContext = ctx;
			ttsVoiceName = "";
			ttsRequests = 0;
			final float speechRate = cSpeechRate;

			private Locale newLocale = new Locale(language);
			// #3344: Try Locale builder instead of constructor (only available from API 21)
			if (android.os.Build.VERSION.SDK_INT >= 21) {
				try {
					final String[] languageFields = language.split("\\_");
					newLocale = new Locale.Builder().setLanguage(languageFields[0]).setScript("").setRegion(languageFields[1]).build();
				} catch (RuntimeException e) {
					// Falls back to "new Locale(language)"
				}
			}

			mTts = new TextToSpeech(ctx, new OnInitListener() {
				@Override
				public void onInit(int status) {
					if (status != TextToSpeech.SUCCESS) {
						ttsVoiceName = "NO INIT SUCCESS";
						internalClear();
					} else if (mTts != null) {
						speechAllowed = true;
						switch (mTts.isLanguageAvailable(newLocale)) {
							case TextToSpeech.LANG_MISSING_DATA:
								ttsVoiceName = newLocale.getDisplayName() + ": LANG_MISSING_DATA";
								if (isSettingsActivity(act)) {
									AlertDialog.Builder builder = createAlertDialog(
										R.string.tts_missing_language_data_title,
										R.string.tts_missing_language_data,
										new IntentStarter(
												act,
												TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
										act);
									builder.show();
								}
								// Proceed anyway in this case
								//break;
							case TextToSpeech.LANG_AVAILABLE:
								ttsVoiceName = "".equals(ttsVoiceName) ? newLocale.getDisplayName() + ": LANG_AVAILABLE" : ttsVoiceName;
							case TextToSpeech.LANG_COUNTRY_AVAILABLE:
								ttsVoiceName = "".equals(ttsVoiceName) ? newLocale.getDisplayName() + ": LANG_COUNTRY_AVAILABLE" : ttsVoiceName;
							case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
								ttsVoiceName = "".equals(ttsVoiceName) ? newLocale.getDisplayName() + ": LANG_COUNTRY_VAR_AVAILABLE" : ttsVoiceName;
								mTts.setLanguage(newLocale);
								if (android.os.Build.VERSION.SDK_INT >= 21) {
									if (mTts.getVoice() != null) {
										ttsVoiceName = ttsVoiceName + "\n\n" + mTts.getVoice().toString();
									}
								} else {
										ttsVoiceName = ttsVoiceName + "\n\n" + mTts.getLanguage() + " (Voice details not reported in API<21)";
								}
								if(speechRate != 1) {
									mTts.setSpeechRate(speechRate);
								}
								break;
							case TextToSpeech.LANG_NOT_SUPPORTED:
								//maybe weird, but I didn't want to introduce parameter in around 5 methods just to do this if condition
								ttsVoiceName = newLocale.getDisplayName() + ": LANG_NOT_SUPPORTED";
								if (isSettingsActivity(act)) {
									AlertDialog.Builder builder = createAlertDialog(
											R.string.tts_language_not_supported_title,
											R.string.tts_language_not_supported,
											new IntentStarter(
													act,
													Intent.ACTION_VIEW, Uri.parse("market://search?q=text to speech engine"
														)),
											act);
									builder.show();
								}
								break;
						}
					}
				}

				private boolean isSettingsActivity(final Context ctx) {
					return ctx instanceof SettingsActivity;
				}
			});
			mTts.setOnUtteranceCompletedListener(new OnUtteranceCompletedListener() {
				// The call back is on a binder thread.
				@Override
				public synchronized void onUtteranceCompleted(String utteranceId) {
					if (--ttsRequests <= 0)
						abandonAudioFocus();
					log.debug("ttsRequests="+ttsRequests);
					if (ttsRequests < 0) {
						ttsRequests = 0;
					}
				}
			});
		}
	}

	public static String getTtsVoiceName() {
		return ttsVoiceName;
	}

	private AlertDialog.Builder createAlertDialog(int titleResID, int messageResID,
			IntentStarter intentStarter, final Activity ctx) {
		AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
		builder.setCancelable(true);
		builder.setNegativeButton(R.string.shared_string_no, null);
		builder.setPositiveButton(R.string.shared_string_yes, intentStarter);
		builder.setTitle(titleResID);
		builder.setMessage(messageResID);
		return builder;
	}
		
	private void internalClear() {
		ttsRequests = 0;
		speechAllowed = false;
		if (mTts != null) {
			mTts.shutdown();
			mTts = null;
		}
		abandonAudioFocus();
		mTtsContext = null;
		ttsVoiceName = "";
	}
	
	@Override
	public void clear() {
		super.clear();
		internalClear();
	}
	
	public static boolean isMyData(File voiceDir) {
		return new File(voiceDir, CONFIG_FILE).exists();
	}

	@Override
	public void updateAudioStream(int streamType) {
		super.updateAudioStream(streamType);
		params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, streamType+"");		
	}

	@Override
	public boolean supportsStructuredStreetNames() {
		return getCurrentVersion() >= 103;
	}

}
