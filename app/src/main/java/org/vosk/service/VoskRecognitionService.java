// Copyright 2020 Ciaran O'Reilly
// Copyright 2019 Alpha Cephei Inc.
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

package org.vosk.service;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.speech.RecognitionService;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.service.utils.PreferenceConstants;
import org.vosk.service.utils.Tools;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

public class VoskRecognitionService extends RecognitionService implements RecognitionListener {
    private final static String TAG = VoskRecognitionService.class.getSimpleName();
    private Recognizer recognizer;
    private SpeechService speechService;
    private Model model;

    private RecognitionService.Callback mCallback;

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    @Override
    protected void onStartListening(Intent intent, Callback callback) {
        mCallback = callback;
        Log.i(TAG, "onStartListening");
        runRecognizerSetup();
    }

    @Override
    protected void onCancel(Callback callback) {
        Log.i(TAG, "onCancel");
        results(new Bundle(), true);
    }

    @Override
    protected void onStopListening(Callback callback) {
        Log.i(TAG, "onStopListening");
        results(new Bundle(), true);
    }

    private void runRecognizerSetup() {
        if (this.model == null) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

            if (sharedPreferences.contains(PreferenceConstants.ACTIVE_MODEL)) {
                File outputFile = new File(Tools.getModelFileRootPath(this),
                        sharedPreferences.getString(PreferenceConstants.ACTIVE_MODEL, "") + "/" + sharedPreferences.getString(PreferenceConstants.ACTIVE_MODEL, ""));

                compositeDisposable.add(Single.fromCallable(() -> new Model(outputFile.getAbsolutePath()))
                        .doOnSuccess(model_ -> this.model = model_)
                        .delay(1, TimeUnit.MICROSECONDS)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(model_ -> startSpeech(), Throwable::printStackTrace));
            }
        } else {
            startSpeech();
        }
    }

    private void startSpeech() {
        setupRecognizer();
        this.readyForSpeech(new Bundle());
        beginningOfSpeech();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.cancel();
            speechService.shutdown();
        }
    }

    private void setupRecognizer() {
        try {
            if (recognizer == null) {
                Log.i(TAG, "Creating recognizer");

                recognizer = new Recognizer(model, 16000.0f);
            }

            if (speechService == null) {
                Log.i(TAG, "Creating speechService");

                speechService = new SpeechService(recognizer, 16000.0f);
            } else {
                speechService.cancel();
            }
            speechService.startListening(this);
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    private void readyForSpeech(Bundle bundle) {
        try {
            mCallback.readyForSpeech(bundle);
        } catch (RemoteException e) {
            // empty
        }
    }

    private void results(Bundle bundle, boolean isFinal) {
        try {
            if (isFinal) {
                speechService.cancel();
                mCallback.results(bundle);
            } else {
                mCallback.partialResults(bundle);
            }
        } catch (RemoteException e) {
            // empty
        }
    }

    private Bundle createResultsBundle(String hypothesis) {
        ArrayList<String> hypotheses = new ArrayList<>();
        hypotheses.add(hypothesis);
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION, hypotheses);
        return bundle;
    }

    private void beginningOfSpeech() {
        try {
            mCallback.beginningOfSpeech();
        } catch (RemoteException e) {
            // empty
        }
    }

    private void error(int errorCode) {
        if (speechService != null) {
            speechService.cancel();
        }
        try {
            mCallback.error(errorCode);
        } catch (RemoteException e) {
            // empty
        }
    }

    Type mapType = new TypeToken<Map<String, String>>() {}.getType();

    @Override
    public void onResult(String hypothesis) {
        if (hypothesis != null) {
            Log.i(TAG, hypothesis);
            Gson gson = new Gson();
            Map<String, String> map = gson.fromJson(hypothesis, mapType);
            String text = map.get("text");
            results(createResultsBundle(text), true);
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        if (hypothesis != null) {
            Log.i(TAG, hypothesis);
            Gson gson = new Gson();
            Map<String, String> map = gson.fromJson(hypothesis, mapType);
            String text = map.get("text");
            results(createResultsBundle(text), true);
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        if (hypothesis != null) {
            Log.i(TAG, hypothesis);
            Gson gson = new Gson();
            Map<String, String> map = gson.fromJson(hypothesis, mapType);
            String text = map.get("partial");
            results(createResultsBundle(text), false);
        }
    }

    @Override
    public void onError(Exception e) {
        Log.e(TAG, e.getMessage());
        error(android.speech.SpeechRecognizer.ERROR_CLIENT);
    }

    @Override
    public void onTimeout() {
        speechService.cancel();
        speechService.startListening(this);
    }
}
