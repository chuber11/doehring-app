package org.pytorch.demo.speechrecognition;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
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
import android.view.View;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.extensions.OrtxPackage;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.google.gson.Gson;

import org.json.JSONObject;

enum StateOption {
    WAITING, LOADING, RECORDING, RECOGNIZING
}

class State {
    private StateOption state = null;
    private MainActivity main;

    public State(MainActivity main) {
        this.main = main;
    }

    public void set(StateOption new_state) {
        set(new_state, null);
    }
    public void set(StateOption new_state, String result) {
        main.runOnUiThread(() -> {
            if(new_state == StateOption.WAITING) {
                if(result != null) {
                    this.main.mTextView.setText(result);
                }
                this.main.mButton.setEnabled(true);
                this.setEnabled(true);
                this.main.mButton.setText("Drücken und gedrückt halten zum Aufnehmen");
            } else if(new_state == StateOption.LOADING) {
                this.setEnabled(false);
                this.main.mButton.setEnabled(false);
                this.main.mButton.setText("Modell lädt, bitte warten...");
            } else if(new_state == StateOption.RECORDING) {
                this.setEnabled(false);
                this.main.mButton.setText("Gedrückt halten um weiter aufzunehmen...");
            } else if(new_state == StateOption.RECOGNIZING) {
                this.main.mButton.setEnabled(false);
                this.setEnabled(false);
                this.main.mTextView.setText("");
                this.main.mTextView2.setText("");
                this.main.mButton.setText("Antwort wird generiert...");
            }
        });
        state = new_state;
    }

    private void setEnabled(boolean enabled) {
        for (int i = 0; i < this.main.mRadioGroup.getChildCount(); i++) {
            View child = this.main.mRadioGroup.getChildAt(i);

            if (child instanceof RadioButton) {
                RadioButton radioButton = (RadioButton) child;
                radioButton.setEnabled(enabled);
            }
        }
        this.main.mCheckbox.setEnabled(enabled);
    }

    public boolean is(StateOption other_state) {
        return state == other_state;
    }
}

public class MainActivity extends AppCompatActivity implements Runnable {
    private static final String TAG = MainActivity.class.getName();

    private OrtEnvironment ortEnvironment;
    private OrtSession ortSession;

    private State state;

    protected Button mButton;
    protected TextView mTextView;
    protected TextView mTextView2;
    protected RadioGroup mRadioGroup;
    protected CheckBox mCheckbox;

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mButton = findViewById(R.id.btnRecognize);
        mTextView = findViewById(R.id.tv1);
        mTextView2 = findViewById(R.id.tv2);
        mRadioGroup = findViewById(R.id.radio);
        mCheckbox = findViewById(R.id.play_audio);

        state = new State(this);

        mButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // Handle button press
                    state.set(StateOption.RECORDING);
                    Thread thread = new Thread(MainActivity.this);
                    thread.start();
                    return true; // Indicate the event is handled

                case MotionEvent.ACTION_UP:
                    // Handle button release
                    if(state.is(StateOption.RECORDING)) {
                        state.set(StateOption.RECOGNIZING);
                    }
                    return true; // Indicate the event is handled
            }
            return false;
        });

        mRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            Thread thread = new Thread(this::load_model);
            thread.start();
        });

        Thread thread = new Thread(this::load_model);
        thread.start();

        requestMicrophonePermission();
    }

    private void load_model() {
        if (this.mRadioGroup.getCheckedRadioButtonId() == R.id.model_kit) {
            return;
        }

        state.set(StateOption.LOADING);

        if (ortSession != null) {
            try {
                ortSession.close();
                ortSession = null;
            } catch (OrtException e) {
                e.printStackTrace();
            }
        }
        try {
            ortEnvironment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            sessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
            sessionOptions.addNnapi();

            String model_name;
            if (this.mRadioGroup.getCheckedRadioButtonId() == R.id.model_small) {
                model_name = "model_small_doehring.onnx";
            } else if (this.mRadioGroup.getCheckedRadioButtonId() == R.id.model_large) {
                model_name = "model_large-v3_doehring.onnx";
            } else {
                throw new RuntimeException();
            }
            String model_path = assetFilePath(getApplicationContext(), model_name);
            ortSession = ortEnvironment.createSession(model_path, sessionOptions);
        } catch (OrtException e) {
            e.printStackTrace();
        }

        state.set(StateOption.WAITING);
    }

    private void requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final int REQUEST_RECORD_AUDIO = 13;
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.INTERNET}, REQUEST_RECORD_AUDIO);
        }
    }

    private String assetFilePath(Context context, String assetName) {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, assetName + ": " + e.getLocalizedMessage());
        }
        return null;
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

        while (state.is(StateOption.RECORDING) && shortsRead < RECORDING_LENGTH_MAX) {
            int numberOfShort = record.read(audioBuffer, 0, audioBuffer.length);
            shortsRead += numberOfShort;
            for (int i = 0; i < numberOfShort; ++i) {
                recordingBuffer.add(audioBuffer[i]);
            }
        }
        state.set(StateOption.RECOGNIZING);

        record.stop();
        record.release();

        float[] floatInputBuffer = convertListToFloatArray(recordingBuffer);

        long startTime = System.currentTimeMillis();
        final String transcript = recognize(floatInputBuffer);
        long endTime = System.currentTimeMillis();

        mTextView2.setText(String.format("Latenz ASR: %.2f s",(endTime-startTime)/1000f));

        if (mCheckbox.isChecked()) {
            play(transcript);
        }

        state.set(StateOption.WAITING, transcript);
    }

    private String recognize(float[] floatInputBuffer) {
        String transcript = "ERROR";

        if (this.mRadioGroup.getCheckedRadioButtonId() == R.id.model_kit) {
            Gson gson = new Gson();
            float[] floatSend = new float[floatInputBuffer.length];
            for (int i=0; i<floatInputBuffer.length; i++) {
                floatSend[i] = floatInputBuffer[i] / 128;
            }
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

        try {
            Map<String, OnnxTensor> inputMap = new HashMap<>();

            FloatBuffer audioBuffer = FloatBuffer.wrap(floatInputBuffer);
            OnnxTensor audioStreamTensor = OnnxTensor.createTensor(ortEnvironment, audioBuffer, new long[]{1, floatInputBuffer.length});
            inputMap.put("audio_pcm", audioStreamTensor);

            inputMap.put("max_length", OnnxTensor.createTensor(ortEnvironment,
                    IntBuffer.wrap(new int[] {80}),
                    new long[]{1}));
            inputMap.put("min_length", OnnxTensor.createTensor(ortEnvironment,
                    IntBuffer.wrap(new int[] {0}),
                    new long[]{1}));
            inputMap.put("num_beams", OnnxTensor.createTensor(ortEnvironment,
                    IntBuffer.wrap(new int[] {4}),
                    new long[]{1}));
            inputMap.put("num_return_sequences", OnnxTensor.createTensor(ortEnvironment,
                    IntBuffer.wrap(new int[] {1}),
                    new long[]{1}));
            inputMap.put("length_penalty", OnnxTensor.createTensor(ortEnvironment,
                    FloatBuffer.wrap(new float[] {1.0f}),
                    new long[]{1}));
            inputMap.put("repetition_penalty", OnnxTensor.createTensor(ortEnvironment,
                    FloatBuffer.wrap(new float[] {1.0f}),
                    new long[]{1}));
            int[] decoder_input_ids;
            if (this.mRadioGroup.getCheckedRadioButtonId() == R.id.model_small) {
                decoder_input_ids = new int[] {50258, 50261, 50359, 50363};

            } else if (this.mRadioGroup.getCheckedRadioButtonId() == R.id.model_large) {
                decoder_input_ids = new int[] {50258, 50261, 50360, 50364};
            } else {
                throw new RuntimeException();
            }
            inputMap.put("decoder_input_ids", OnnxTensor.createTensor(ortEnvironment,
                    IntBuffer.wrap(decoder_input_ids),
                    new long[]{1,4})); // en: 50259, de: 50261

            OrtSession.Result result = ortSession.run(inputMap);

            // Assuming model output is a tensor of strings
            OnnxValue outputValue = result.get(0);
            if (outputValue instanceof OnnxTensor) {
                OnnxTensor outputTensor = (OnnxTensor) outputValue;
                // Assuming the output is a string tensor
                String[][] outputStrings = (String[][]) outputTensor.getValue();
                transcript = outputStrings[0][0]; // Get the first transcription
            }

            // Release resources
            result.close();
        } catch (OrtException e) {
            e.printStackTrace();
        }

        return transcript;
    }

    private void play(String text) {
        long startTime = System.currentTimeMillis();

        if (true) { //this.mRadioGroup.getCheckedRadioButtonId() == R.id.model_kit) {
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
                        byte[] audioData = Base64.decode(base64Audio, Base64.DEFAULT);

                        add_latency_to_textfield(startTime);

                        // Play the decoded audio data
                        playPcmAudio(audioData);
                    } else {
                        System.err.println("Request failed: " + response.code());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        } else {
            throw new RuntimeException(); // TODO: implement

            //add_latency_to_textfield(startTime);
        }
    }

    private void add_latency_to_textfield(long startTime) {
        long endTime = System.currentTimeMillis();
        String asr_text = (String) mTextView2.getText();
        asr_text += String.format(", Latenz TTS: %.2f s",(endTime-startTime)/1000f);
        mTextView2.setText(asr_text);
    }

    private void playPcmAudio(byte[] audioData) {
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
        audioTrack.write(audioData, 0, audioData.length);
        audioTrack.stop();
        audioTrack.release();
    }
}