package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

/**
 * Local addition (home_hold_switch): "Switch to Google TV home?" when Home is held. A see-through activity, since
 * HomeKeyService can't show a dialog itself. Switch is focused, so OK confirms; Back or Cancel leaves things as they are.
 */
public class SwitchHomeActivity extends Activity {
    private static final String PREFS = "home_switch";

    /** True while the owner chose the stock home screen; kept across restarts. */
    static boolean stockChosen(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("stock", false);
    }

    static void setStockChosen(Context c, boolean stock) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("stock", stock).apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean toStock = !stockChosen(this);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(toStock ? R.string.home_switch_ask_stock : R.string.home_switch_ask_new)
                .setMessage(toStock ? R.string.home_switch_hint_stock : R.string.home_switch_hint_new)
                .setPositiveButton(R.string.home_switch_yes, (d, w) -> switchTo(toStock))
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> finish())
                .create();
        dialog.setOnShowListener(d -> {
            Button yes = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (yes != null) yes.requestFocus();
        });
        dialog.show();
    }

    private void switchTo(boolean stock) {
        setStockChosen(this, stock);
        String stockPkg = getString(R.string.stock_home_redirect);
        Intent home = stock && !stockPkg.isEmpty()
                ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(stockPkg)
                : new Intent(this, MainActivity.class).addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(home);
        } catch (ActivityNotFoundException | SecurityException e) {
            setStockChosen(this, false);   // never leave the TV without a home screen
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
