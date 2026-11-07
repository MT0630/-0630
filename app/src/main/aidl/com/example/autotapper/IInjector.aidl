package com.example.autotapper;

// Shizuku のユーザーサービス（shell 権限で動く別プロセス）とのやりとり
interface IInjector {
    // Shizuku が終了時に呼ぶ（番号は Shizuku の決まり）
    void destroy() = 16777114;

    // (x, y) を durationMs ミリ秒押して離す。成功したら true
    boolean tap(float x, float y, long durationMs) = 1;

    // 動作確認用
    String check() = 2;
}
