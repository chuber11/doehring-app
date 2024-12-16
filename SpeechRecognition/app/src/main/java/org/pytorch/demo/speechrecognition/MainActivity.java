package org.pytorch.demo.speechrecognition;

import static android.view.View.VISIBLE;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.google.gson.Gson;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

enum StateOption {
    INIT, RECORDING, RECOGNIZING, GENERATING, PLAYING, SILENCE, EDITING
}

class State {
    protected StateOption state = StateOption.INIT;
    private boolean back_pressed = false;
    private MainActivity main;

    public State(MainActivity main) {
        this.main = main;
    }

    public void set(StateOption new_state) {
        set(new_state, null, null);
    }
    public void set(StateOption new_state, String result, String latency) {
        main.runOnUiThread(() -> {
            switch(new_state) {
                case INIT:
                    break;
                case RECORDING:
                    this.main.buttonRecord.setText("gedrückt halten um weiter aufzunehmen...");
                    this.main.buttonPlay.setEnabled(false);
                    this.main.buttonBack.setEnabled(false);
                    this.main.buttonCorrect.setEnabled(false);
                    break;
                case RECOGNIZING:
                    this.main.buttonRecord.setEnabled(false);
                    this.main.buttonPlay.setEnabled(false);
                    this.main.buttonBack.setEnabled(false);
                    this.main.buttonCorrect.setEnabled(false);
                    if(!back_pressed) {
                        this.main.textViewText.setText("");
                    }
                    this.main.textViewLatencyASR.setText("");
                    this.main.textViewLatencyTTS.setText("");
                    this.main.buttonRecord.setText("Audio wird transkribiert...");
                    break;
                case GENERATING:
                    this.main.buttonRecord.setEnabled(false);
                    this.main.buttonRecord.setText("Audio wird generiert...");
                    this.main.buttonPlay.setEnabled(false);
                    this.main.buttonPlay.setText("abspielen");
                    break;
                case PLAYING:
                    this.main.buttonPlay.setText("stop");
                    this.main.buttonRecord.setText("drücken und gedrückt halten zum Aufnehmen");
                    break;
                case SILENCE:
                    if(result != null) {
                        String text = result;
                        boolean enabled = false;
                        if(latency != null) { // ASR finished
                            if(this.main.textViewText.getText().equals("")) {
                                enabled = true;
                            }
                            if(back_pressed) {
                                text = this.main.textViewText.getText()+" "+lowercaseFirstLetter(result);
                            }
                            back_pressed = false;
                            this.main.textViewLatencyASR.setText(latency);
                        } else { // Back button pressed
                            back_pressed = true;
                        }
                        this.main.textViewText.setText(text);
                        this.main.queue.add(text);
                        this.main.buttonPlay.setEnabled(false);
                        this.main.buttonBack.setEnabled(true);
                        this.main.buttonCorrect.setEnabled(enabled);
                        this.main.buttonRecord.setText("drücken und gedrückt halten zum Aufnehmen");
                    } else if (latency != null) { // TTS finished
                        this.main.textViewLatencyTTS.setText(latency);
                        this.main.buttonRecord.setEnabled(true);
                        this.main.buttonPlay.setEnabled(true);
                        this.main.buttonRecord.setText("drücken und gedrückt halten zum Aufnehmen");
                        this.main.buttonPlay.setText("abspielen");
                    } else { // Playing finished
                        this.main.buttonPlay.setText("abspielen");
                    }
                    break;
                case EDITING:
                    String text = (String)this.main.textViewText.getText();
                    if(!text.equals("")) { // go to correction mode
                        this.main.textViewText.setText("");
                        this.main.textViewCorrect.setText(text);
                        this.main.textViewText.setVisibility(View.INVISIBLE);
                        this.main.textViewCorrect.setVisibility(VISIBLE);
                        this.main.buttonCorrect.setText("fertig");
                        this.main.buttonRecord.setEnabled(false);
                        this.main.buttonBack.setEnabled(false);
                        this.main.buttonPlay.setEnabled(false);
                    } else { // correction finished
                        String new_text = this.main.textViewCorrect.getText().toString();
                        this.main.buttonCorrect.setText("korrigieren");
                        this.main.textViewText.setText(new_text);
                        this.main.textViewText.setVisibility(VISIBLE);
                        this.main.textViewCorrect.setVisibility(View.INVISIBLE);
                        this.main.queue.add(new_text);
                        this.main.buttonRecord.setEnabled(true);
                        //this.main.buttonBack.setEnabled(true);
                        this.main.buttonPlay.setEnabled(true);

                        AlertDialog.Builder builder = new AlertDialog.Builder(this.main);
                        builder.setTitle("Frage:");
                        builder.setMessage("Soll aus diesem Beispiel gelernt werden? (das kann nicht rückgängig gemacht werden!)");

                        // Add "Yes" button and execute method if clicked
                        builder.setPositiveButton("Ja", (dialog, which) -> {
                            new Thread(() -> send_sample(this.main.lastAudio, new_text)).start();
                        });
                        builder.setNegativeButton("Nein", (dialog, which) -> {
                            dialog.dismiss();
                        });

                        builder.create().show();
                    }
                    break;
            }
        });
        state = new_state;
    }

    public void send_sample(float[] audio, String transcript) {
        Map<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("audio", audio);
        jsonMap.put("transcript", transcript);

        Gson gson = new Gson();
        String jsonArray = gson.toJson(jsonMap);

        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(jsonArray, JSON);
        Request request = new Request.Builder()
                .url("https://lt2srv-backup.iar.kit.edu/webapi/asr_label_doehring")
                .post(body)
                .build();

        OkHttpClient client = new OkHttpClient();

        // Send the request and process the response
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            // Process the response
            //String responseBody = response.body().string();
            //System.out.println("Response: " + responseBody);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String lowercaseFirstLetter(String input) {
        if (input == null || input.isEmpty()) {
            return input; // Return as is if the string is null or empty
        }
        return input.substring(0, 1).toLowerCase() + input.substring(1);
    }
}

public class MainActivity extends AppCompatActivity implements Runnable {
    private static final String TAG = MainActivity.class.getName();

    private State state;
    private byte[] audioData = null;

    protected Button buttonRecord;
    protected Button buttonPlay;
    protected Button buttonBack;
    protected Button buttonCorrect;
    protected TextView textViewText;
    protected TextView textViewLatencyASR;
    protected TextView textViewLatencyTTS;
    protected EditText textViewCorrect;

    protected float[] lastAudio;

    protected final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        buttonRecord = findViewById(R.id.btnRecognize);
        buttonPlay = findViewById(R.id.btnPlay);
        buttonBack = findViewById(R.id.btnBack);
        buttonCorrect = findViewById(R.id.btnCorrect);

        textViewText = findViewById(R.id.tvText);
        textViewLatencyASR = findViewById(R.id.tvLatencyASR);
        textViewLatencyTTS = findViewById(R.id.tvLatencyTTS);
        textViewCorrect = findViewById(R.id.tvEdit);

        state = new State(this);

        buttonRecord.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // Handle button press
                    state.set(StateOption.RECORDING);
                    Thread thread = new Thread(MainActivity.this);
                    thread.start();
                    return true; // Indicate the event is handled
                case MotionEvent.ACTION_UP:
                    // Handle button release
                    if (state.state == StateOption.RECORDING) {
                        state.set(StateOption.RECOGNIZING);
                    }
                    return true; // Indicate the event is handled
            }
            return false;
        });

        buttonPlay.setOnClickListener(view -> new Thread(this::playPcmAudio).start());

        buttonCorrect.setOnClickListener(view -> state.set(StateOption.EDITING));

        buttonBack.setOnClickListener(view -> {
            String text = (String)textViewText.getText();
            int lastSpaceIndex = text.trim().lastIndexOf(' ');
            if (lastSpaceIndex != -1) {
                text = text.substring(0, lastSpaceIndex);
            } else {
                text = "";
            }
            state.set(StateOption.SILENCE, text, null);
            queue.add(text);
        });

        new Thread(() -> {
            while (true) {
                String transcript;
                try {
                    transcript = queue.poll(Long.MAX_VALUE, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                String res;
                while((res = queue.poll()) != null) {
                    transcript = res;
                }
                generate(transcript);
            }
        }).start();

        requestMicrophonePermission();
    }

    private void requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final int REQUEST_RECORD_AUDIO = 13;
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.INTERNET}, REQUEST_RECORD_AUDIO);
        }
    }

    public static float[] convertListToFloatArray(List<Short> floatList) {
        float[] floatArray = new float[floatList.size()];
        for (int i = 0; i < floatList.size(); i++) {
            floatArray[i] = ((float)floatList.get(i))/128f-1;
        }
        return floatArray;
    }

    public void run() {
        state.set(StateOption.RECORDING);

        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        final int SAMPLE_RATE = 16000;

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!");
            return;
        }

        record.startRecording();

        long shortsRead = 0;

        final int AUDIO_LEN_IN_SECOND_MAX = 20;
        final int RECORDING_LENGTH_MAX = SAMPLE_RATE * AUDIO_LEN_IN_SECOND_MAX;

        short[] audioBuffer = new short[bufferSize / 2];
        List<Short> recordingBuffer = new ArrayList<>();

        while (state.state == StateOption.RECORDING && shortsRead < RECORDING_LENGTH_MAX) {
            int numberOfShort = record.read(audioBuffer, 0, audioBuffer.length);
            shortsRead += numberOfShort;
            for (int i = 0; i < numberOfShort; ++i) {
                recordingBuffer.add(audioBuffer[i]);
            }
        }

        record.stop();
        record.release();

        float[] floatInputBuffer = convertListToFloatArray(recordingBuffer);

        long startTime = System.currentTimeMillis();
        final String transcript = recognize(floatInputBuffer);
        long endTime = System.currentTimeMillis();

        String latency = String.format("Latenz ASR: %.2f s",(endTime-startTime)/1000f);
        state.set(StateOption.SILENCE, transcript, latency);
    }

    private String recognize(float[] floatInputBuffer) {
        String transcript = "ERROR";

        Gson gson = new Gson();
        float[] floatSend = new float[floatInputBuffer.length];
        for (int i=0; i<floatInputBuffer.length; i++) {
            floatSend[i] = floatInputBuffer[i] / 128;
        }
        lastAudio = floatSend;
        String jsonArray = gson.toJson(floatSend);

        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(jsonArray, JSON);

        Request request = new Request.Builder()
            .url("https://lt2srv-backup.iar.kit.edu/webapi/asr_inference_doehring")
            .post(body)
            .build();

        OkHttpClient client = new OkHttpClient();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            // Process the response
            transcript = response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return transcript;
    }

    private void generate(String text) {
        state.set(StateOption.GENERATING);

        long startTime = System.currentTimeMillis();

        String url = "https://lt2srv-backup.iar.kit.edu/webapi/tts_inference_doehring";
        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        String json = "{\"text\": \"" + text + "\"}";

        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        OkHttpClient client = new OkHttpClient();

        new Thread(() -> {
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    JSONObject responseJson = new JSONObject(response.body().string());
                    String base64Audio = responseJson.getString("audio");

                    // Decode the Base64 string to raw PCM data
                    audioData = Base64.decode(base64Audio, Base64.DEFAULT);
                } else {
                    System.err.println("Request failed: " + response.code());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            long endTime = System.currentTimeMillis();
            String latency = String.format("Latenz TTS: %.2f s",(endTime-startTime)/1000f);
            state.set(StateOption.SILENCE, null, latency);
        }).start();
    }

    private void playPcmAudio() {
        if(audioData == null) {
            return;
        }

        if(state.state == StateOption.PLAYING) {
            state.set(StateOption.SILENCE);
            return;
        }
        state.set(StateOption.PLAYING);

        int sampleRate = 16000; // Set the correct sample rate
        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        // Determine the buffer size
        int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);

        AudioTrack audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM
        );

        audioTrack.play();

        // Write audio data to AudioTrack
        int offset = 0;
        while (state.state == StateOption.PLAYING) {
            int chunkSize = Math.min(bufferSize, audioData.length - offset);
            audioTrack.write(audioData, offset, chunkSize);

            offset += chunkSize;
            if (offset >= audioData.length) {
                break;
            }
        }
        audioTrack.stop();
        audioTrack.release();

        if(state.state == StateOption.PLAYING) {
            state.set(StateOption.SILENCE);
        }
    }
}