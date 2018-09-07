package org.totschnig.myexpenses.util.crashreporting;

import android.content.Context;
import android.util.Log;

import com.crashlytics.android.Crashlytics;

import org.totschnig.myexpenses.MyApplication;
import org.totschnig.myexpenses.preference.PrefKey;

import io.fabric.sdk.android.Fabric;
import timber.log.Timber;

public class CrashHandlerImpl extends CrashHandler {

  private CrashReportingTree crashReportingTree;

  @Override
  public void onAttachBaseContext(MyApplication application) {

  }

  @Override
  void setupLoggingDo(Context context) {
    Fabric.with(context, new Crashlytics());
    if (crashReportingTree == null) {
      crashReportingTree = new CrashReportingTree();
      Timber.plant(crashReportingTree);
    }
    putCustomData("UserEmail", PrefKey.CRASHREPORT_USEREMAIL.getString(null));
  }

  @Override
  void putCustomData(String key, String value) {
    if (Fabric.isInitialized()) {
      Crashlytics.setString(key, value);
    }
  }

  private static class CrashReportingTree extends Timber.Tree {
    @Override
    protected void log(int priority, String tag, String message, Throwable t) {
      if (priority == Log.ERROR && t != null) {
        Crashlytics.logException(t);
      } else if (message != null) {
        Crashlytics.log(message);
      }
    }

    @Override
    protected boolean isLoggable(String tag, int priority) {
      return priority == Log.ERROR || priority == Log.WARN;
    }
  }
}
