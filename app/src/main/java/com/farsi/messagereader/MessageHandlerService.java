package com.farsi.messagereader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MessageHandlerService extends Service {

    private static final String TAG = "FarsiMsgReader";
    private static final String CHANNEL_ID = "farsi_msg_reader_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_NEW_MESSAGE = "com.farsi.messagereader.NEW_MESSAGE";
    public static final String ACTION_TEST = "com.farsi.messagereader.TEST";

    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private boolean ttsReady = false;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    private String pendingSender = "";
    private String pendingMessage = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ─── Positive answer keywords in Farsi/Persian ───────────────────────────
    private static final String[] POSITIVE_KEYWORDS = {
        "بله", "آره", "بلی", "بخون", "بخوان", "آرِه", "اره",
        "yes", "yeah", "yep", "ok", "okay"
    };

    private static final String[] NEGATIVE_KEYWORDS = {
        "نه", "نخیر", "نخون", "نخوان", "no", "nope", "cancel"
    };

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        initTTS();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildForegroundNotification("در حال اجرا..."));

        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_NEW_MESSAGE.equals(action) || ACTION_TEST.equals(action)) {
                if (!isProcessing.get()) {
                    pendingSender = intent.getStringExtra("sender");
                    pendingMessage = intent.getStringExtra("message");
                    if (pendingSender == null) pendingSender = "ناشناس";
                    if (pendingMessage == null) pendingMessage = "";
                    mainHandler.postDelayed(this::askUserToRead, 500);
                }
            }
        }
        return START_STICKY;
    }

    // ─── TTS Initialization ──────────────────────────────────────────────────

    private void initTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Try to set Persian/Farsi locale
                Locale fa = new Locale("fa", "IR");
                int result = tts.setLanguage(fa);

                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Farsi TTS not available natively, using default");
                    // Fallback: still works but may have accent
                    tts.setLanguage(Locale.getDefault());
                }

                // Tune for natural Farsi speech
                tts.setSpeechRate(0.90f);   // slightly slower for clarity
                tts.setPitch(1.05f);         // natural pitch

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {}

                    @Override
                    public void onDone(String utteranceId) {
                        if ("ASK_QUESTION".equals(utteranceId)) {
                            mainHandler.postDelayed(
                                MessageHandlerService.this::startListening, 600);
                        } else if ("READ_MESSAGE".equals(utteranceId)) {
                            isProcessing.set(false);
                            abandonAudioFocus();
                            updateNotification("منتظر پیام‌های جدید...");
                        } else if ("DECLINED".equals(utteranceId)) {
                            isProcessing.set(false);
                            abandonAudioFocus();
                            updateNotification("منتظر پیام‌های جدید...");
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        isProcessing.set(false);
                        abandonAudioFocus();
                    }
                });

                ttsReady = true;
                Log.i(TAG, "TTS initialized successfully");
            } else {
                Log.e(TAG, "TTS initialization failed");
            }
        });
    }

    // ─── Ask User ────────────────────────────────────────────────────────────

    private void askUserToRead() {
        if (!ttsReady || pendingMessage.isEmpty()) return;
        isProcessing.set(true);

        requestAudioFocus();
        updateNotification("پیام جدید از: " + pendingSender);

        String question = String.format(
            "پیام جدیدی از %s دریافت شد. آیا مایل به خواندن پیام هستید؟",
            pendingSender
        );

        speakText(question, "ASK_QUESTION");
    }

    // ─── Speech Recognition ──────────────────────────────────────────────────

    private void startListening() {
        if (isListening.get()) return;
        isListening.set(true);

        updateNotification("در انتظار پاسخ شما...");

        mainHandler.post(() -> {
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {

                @Override
                public void onReadyForSpeech(Bundle params) {
                    Log.d(TAG, "Ready for speech");
                }

                @Override
                public void onBeginningOfSpeech() {}

                @Override
                public void onRmsChanged(float rmsdB) {}

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    isListening.set(false);
                }

                @Override
                public void onError(int error) {
                    isListening.set(false);
                    Log.e(TAG, "Speech recognition error: " + error);
                    // On error, politely re-ask
                    mainHandler.postDelayed(() -> {
                        speakText("متوجه نشدم. لطفاً بله یا نه بگویید.", "ASK_AGAIN");
                        mainHandler.postDelayed(
                            MessageHandlerService.this::startListening, 2000);
                    }, 300);
                }

                @Override
                public void onResults(Bundle results) {
                    isListening.set(false);
                    ArrayList<String> matches = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    handleVoiceResult(matches);
                }

                @Override
                public void onPartialResults(Bundle partialResults) {}

                @Override
                public void onEvent(int eventType, Bundle params) {}
            });

            Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR");
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_ALSO_RECOGNIZE_SPEECH, true);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);

            try {
                speechRecognizer.startListening(recognizerIntent);
            } catch (Exception e) {
                Log.e(TAG, "Error starting speech recognizer: " + e.getMessage());
                isListening.set(false);
                isProcessing.set(false);
            }
        });
    }

    // ─── Handle Voice Result ─────────────────────────────────────────────────

    private void handleVoiceResult(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty()) {
            retryListening();
            return;
        }

        String heard = matches.get(0).toLowerCase().trim();
        Log.d(TAG, "Heard: " + heard);

        // Check for positive response
        for (String keyword : POSITIVE_KEYWORDS) {
            if (heard.contains(keyword)) {
                readMessage();
                return;
            }
        }

        // Check for negative response
        for (String keyword : NEGATIVE_KEYWORDS) {
            if (heard.contains(keyword)) {
                declineReading();
                return;
            }
        }

        // Not recognized clearly, retry
        retryListening();
    }

    private void retryListening() {
        speakText("متوجه نشدم. لطفاً بله یا نه بگویید.", "RETRY");
        mainHandler.postDelayed(this::startListening, 2500);
    }

    // ─── Read Message ─────────────────────────────────────────────────────────

    private void readMessage() {
        updateNotification("در حال خواندن پیام...");
        String fullText = String.format("پیام از %s: %s", pendingSender, pendingMessage);
        speakText(fullText, "READ_MESSAGE");
    }

    private void declineReading() {
        speakText("باشه. پیام خوانده نشد.", "DECLINED");
    }

    // ─── TTS Helper ──────────────────────────────────────────────────────────

    private void speakText(String text, String utteranceId) {
        if (!ttsReady || tts == null) return;
        tts.stop();
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
    }

    // ─── Audio Focus ─────────────────────────────────────────────────────────

    private void requestAudioFocus() {
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(change -> {})
            .build();

        audioManager.requestAudioFocus(audioFocusRequest);
    }

    private void abandonAudioFocus() {
        if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "خواننده پیام فارسی",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("سرویس خواندن پیام‌ها به فارسی");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildForegroundNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("خواننده پیام فارسی")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIF_ID, buildForegroundNotification(text));
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (speechRecognizer != null) speechRecognizer.destroy();
        abandonAudioFocus();
    }
}
