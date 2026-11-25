package com.auction.client.ui;

/** Decorative animations freeze while a new palette is being revealed. */
interface ThemePausable {
    void pauseForTheme();
    void resumeAfterTheme();
}
