/*
 *  Copyright (C)  2018  Duy Tran Le
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.duy.ccppcompiler.compiler.analyze;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.View;

import com.duy.ccppcompiler.R;
import com.duy.ccppcompiler.compiler.shell.ArgumentBuilder;
import com.duy.ccppcompiler.compiler.shell.CommandResult;
import com.duy.ccppcompiler.compiler.shell.Shell;
import com.duy.common.DLog;
import com.duy.ide.diagnostic.DiagnosticPresenter;
import com.duy.ide.editor.view.IEditAreaView;
import com.jecelyin.editor.v2.Preferences;
import com.duy.ide.editor.IEditorDelegate;
import com.jecelyin.editor.v2.io.LocalFileWriter;

import java.io.File;
import java.io.IOException;

;

/**
 * https://sourceforge.net/p/cppcheck/wiki/Home/
 * <p>
 * # enable warning messages
 * cppcheck --enable=warning file.c
 * <p>
 * # enable performance messages
 * cppcheck --enable=performance file.c
 * <p>
 * # enable information messages
 * cppcheck --enable=information file.c
 * <p>
 * # For historical reasons, --enable=style enables warning, performance,
 * # portability and style messages. These are all reported as "style" when
 * # using the old xml format.
 * cppcheck --enable=style file.c
 * <p>
 * # enable warning and performance messages
 * cppcheck --enable=warning,performance file.c
 * <p>
 * # enable unusedFunction checking. This is not enabled by --enable=style
 * # because it doesn't work well on libraries.
 * cppcheck --enable=unusedFunction file.c
 * <p>
 * # enable all messages
 * cppcheck --enable=all
 */
public class CppCheckAnalyzer implements ICodeAnalyser {
    //1 second
    private static final long DELAY_TIME = 1000;

    private static final String TAG = "CppCheckAnalyzer";

    private static final String CPPCHECK_PROGRAM = "cppcheck";

    private final Context mContext;
    private final IEditorDelegate mEditorDelegate;
    private final android.os.Handler mHandler = new Handler();
    private final IEditAreaView mEditText;
    private DiagnosticPresenter mDiagnosticPresenter;
    private AnalyzeTask mAnalyzeTask;
    private final Runnable mAnalyze = new Runnable() {
        @Override
        public void run() {
            analyzeImpl();
        }
    };

    public CppCheckAnalyzer(@NonNull Context context, @NonNull IEditorDelegate delegate, DiagnosticPresenter diagnosticPresenter) {
        mContext = context;
        mEditorDelegate = delegate;
        mEditText = mEditorDelegate.getEditText();
        mDiagnosticPresenter = diagnosticPresenter;
    }

    private static boolean isVisible(final View view) {
        if (view == null) {
            return false;
        }
        if (!view.isShown()) {
            return false;
        }
        final Rect actualPosition = new Rect();
        view.getGlobalVisibleRect(actualPosition);
        DisplayMetrics displayMetrics = Resources.getSystem().getDisplayMetrics();
        final Rect screen = new Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels);
        return actualPosition.intersect(screen);
    }

    private void analyzeImpl() {
        if (mAnalyzeTask != null) {
            mAnalyzeTask.cancel(true);
        }
        View view = (View) mEditText;
        if (isVisible(view)) {
            mAnalyzeTask = new AnalyzeTask(mContext, mEditorDelegate, mDiagnosticPresenter);
            mAnalyzeTask.execute();
        }
    }


    @Override
    public void start() {

        mEditorDelegate.getEditText().addTextChangedListener(new TextWatcher() {

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                mHandler.removeCallbacks(mAnalyze);
                mHandler.postDelayed(mAnalyze, DELAY_TIME);
            }
        });
    }

    @SuppressLint("StaticFieldLeak")
    private static class AnalyzeTask extends AsyncTask<Void, Void, String> {

        private final Context mContext;
        private final DiagnosticPresenter mDiagnosticPresenter;
        private IEditorDelegate mEditorDelegate;

        public AnalyzeTask(Context mContext, IEditorDelegate delegate, DiagnosticPresenter mDiagnosticPresenter) {
            this.mContext = mContext;
            this.mEditorDelegate = delegate;
            this.mDiagnosticPresenter = mDiagnosticPresenter;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mDiagnosticPresenter.clear();
        }

        @Override
        protected String doInBackground(Void... voids) {
            try {
                IEditAreaView editText = mEditorDelegate.getEditText();

                File originFile = mEditorDelegate.getDocument().getFile();
                File tmpFile = new File(getCppCheckTmpDir(), originFile.getName());
                if (DLog.DEBUG) {
                    DLog.d(TAG, "doInBackground: run cppcheck for " + originFile);
                }

                long startTime = System.currentTimeMillis();
                LocalFileWriter localFileWriter = new LocalFileWriter(tmpFile, "UTF-8");
                try {
                    localFileWriter.writeToFile(editText.getText());
                } catch (IOException e) {
                    e.printStackTrace();
                    return null;
                }
                if (DLog.DEBUG) {
                    DLog.d(TAG, "doInBackground: save time = " +
                            (System.currentTimeMillis() - startTime));
                }

                ArgumentBuilder builder = new ArgumentBuilder(CPPCHECK_PROGRAM);
                builder.addFlags(CppCheckOutputParser.TEMPLATE);
                builder.addFlags(tmpFile.getAbsolutePath());

                String enableFlags = "";
                //flags
                Preferences setting = Preferences.getInstance(mContext);
                boolean warn = setting.getBoolean(mContext.getString(R.string.pref_key_cpp_check_warning), true);
                if (warn) enableFlags += "warning";
                boolean performance = setting.getBoolean(mContext.getString(R.string.pref_key_cpp_check_performance), true);
                if (performance) {
                    if (!enableFlags.isEmpty()) enableFlags += ",";
                    enableFlags += "performance";
                }
                boolean information = setting.getBoolean(mContext.getString(R.string.pref_key_cpp_check_information), false);
                if (information) {
                    if (!enableFlags.isEmpty()) enableFlags += ",";
                    enableFlags += "information";
                }
                if (!enableFlags.isEmpty()) {
                    builder.addFlags("--enable=" + enableFlags);
                }

                String cmd = builder.build();
                CommandResult result = Shell.exec(mContext, tmpFile.getParent(), cmd);
                if (isCancelled()) {
                    return null;
                }

                if (DLog.DEBUG) DLog.d(TAG, "result = " + result);
                return result.getMessage().replace(tmpFile.getAbsolutePath(), originFile.getAbsolutePath());
            } catch (Exception e) {
                return null;
            }
        }

        private File getCppCheckTmpDir() {
            File dir = new File(mContext.getCacheDir(), "cppcheck/tmp");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }

        @Override
        protected void onPostExecute(String message) {
            super.onPostExecute(message);
            if (message != null && !isCancelled()) {
                mDiagnosticPresenter.onNewMessage(message);
            }
        }
    }
}
