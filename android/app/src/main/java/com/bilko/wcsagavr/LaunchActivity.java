package com.bilko.wcsagavr;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * App icon entry point (no UI): starts the game, or the 2D installer panel while the
 * game data is missing or its installation is unfinished.
 */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean install = InstallActivity.needsInstall();
        Log.i("WCSaga", install ? "game data missing: opening the installer" : "starting the game");
        Intent next = new Intent(this, install ? InstallActivity.class : WCSagaActivity.class);
        Intent src = getIntent();
        if (src != null && src.getExtras() != null) next.putExtras(src.getExtras());
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(next);
        finish();
        overridePendingTransition(0, 0);
    }
}
