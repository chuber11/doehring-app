package org.pytorch.demo.speechrecognition;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.ArrayList;

import org.pytorch.LiteModuleLoader;

enum StateOption {
    WAITING, LOADING, RECORDING, RECOGNIZING
}

class State {
    private StateOption state = StateOption.WAITING;
    private Button mButton;
    private TextView mTextView;
    private MainActivity main;

    public State(Button mButton, TextView mTextView, MainActivity main) {
        this.mButton = mButton;
        this.mTextView = mTextView;
        this.main = main;
    }

    public void set(StateOption new_state) {
        set(new_state, null);
    }
    public void set(StateOption new_state, String result) {
        main.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(new_state == StateOption.WAITING) {
                    mTextView.setText(result);
                    mButton.setEnabled(true);
                    mButton.setText("Press and hold to record");
                } else if(new_state == StateOption.LOADING) {
                    mButton.setText("Model is loading, please wait...");
                } else if(new_state == StateOption.RECORDING) {
                    mButton.setText("Hold to continue recording...");
                } else if(new_state == StateOption.RECOGNIZING) {
                    mButton.setEnabled(false);
                    mButton.setText("Generating answer...");
                }
            }
        });
        state = new_state;
    }

    public boolean is(StateOption other_state) {
        return state == other_state;
    }
}

public class MainActivity extends AppCompatActivity implements Runnable {
    private static final String TAG = MainActivity.class.getName();

    private Module module;

    private State state;

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button mButton = findViewById(R.id.btnRecognize);
        TextView mTextView = findViewById(R.id.tvResult);

        state = new State(mButton, mTextView,this);

        mButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
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
            }
        });

        Thread thread = new Thread(new Runnable() {
                public void run() {
                    if (module == null) {
                        module = LiteModuleLoader.load(assetFilePath(getApplicationContext(),
                                "wav2vec2_2.3.ptl"));

                    }
                }
        });
        thread.start();

        requestMicrophonePermission();
    }

    private void requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final int REQUEST_RECORD_AUDIO = 13;
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
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

    public static float[] convertListToFloatArray(List<Float> floatList) {
        float[] floatArray = new float[floatList.size()];
        for (int i = 0; i < floatList.size(); i++) {
            floatArray[i] = floatList.get(i);
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
        List<Float> recordingBuffer = new ArrayList<>();

        while (state.is(StateOption.RECORDING) && shortsRead < RECORDING_LENGTH_MAX) {
            int numberOfShort = record.read(audioBuffer, 0, audioBuffer.length);
            shortsRead += numberOfShort;
            for (int i = 0; i < numberOfShort; ++i) {
                recordingBuffer.add(audioBuffer[i] / (float)Short.MAX_VALUE);
            }
        }
        state.set(StateOption.RECOGNIZING);

        record.stop();
        record.release();

        float[] floatInputBuffer = convertListToFloatArray(recordingBuffer);
        final String result = recognize(floatInputBuffer);

        state.set(StateOption.WAITING, result);
    }

    private String recognize(float[] floatInputBuffer) {
        while (module == null) {
            state.set(StateOption.LOADING);

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG, "Thread.sleep(100) was interrupted!");
            }
        }
        state.set(StateOption.RECOGNIZING);

        FloatBuffer inTensorBuffer = Tensor.allocateFloatBuffer(floatInputBuffer.length);
        for (float val : floatInputBuffer) {
            inTensorBuffer.put(val);
        }

        Tensor inTensor = Tensor.fromBlob(inTensorBuffer, new long[]{1, floatInputBuffer.length});
        final String result = module.forward(IValue.from(inTensor)).toStr();

        return result;
    }
}