package com.example.smarttodo;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

public class BaseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Ui.wrap(newBase));
    }

    /**
     * 跟随系统深浅色时，系统配置变化后重建页面，
     * 避免首页切换了、弹窗/列表还停留在旧主题。
     * 手动指定浅色/深色时不重建。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (Ui.mode(this) == Ui.AUTO) recreate();
    }
}
