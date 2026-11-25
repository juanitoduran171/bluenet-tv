package ec.net.blue.tv;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Detecta cuando la app pasa a segundo plano para volver a bloquear el control parental. */
public class App extends Application {
    private int started;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity a) {
                started++;
            }

            @Override
            public void onActivityStopped(Activity a) {
                started--;
                if (started <= 0 && !a.isChangingConfigurations()) {
                    started = 0;
                    Parental.lock();
                }
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityResumed(Activity a) {
            }

            @Override
            public void onActivityPaused(Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
    }
}
