package chat.rocket.android.custom;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import chat.rocket.android.R;
import chat.rocket.android.authentication.ui.AuthenticationActivity;

public class ActivitySplashScreen extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_splash_screen);
    }

    Handler splashHandler = new Handler();
    Runnable splashRunnable = new Runnable() {
        @Override
        public void run() {
            gotoAuthenticationActivity();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        splashHandler.postDelayed(splashRunnable, 1500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        splashHandler.removeCallbacks(splashRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void gotoAuthenticationActivity() {
        splashHandler.removeCallbacks(splashRunnable);
        startActivity(new Intent(this, AuthenticationActivity.class));
        finish();
    }
}
