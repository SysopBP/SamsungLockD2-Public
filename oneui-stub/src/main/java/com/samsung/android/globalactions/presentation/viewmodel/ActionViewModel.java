/* OneUIX compile-only stub adaptation, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only */
package com.samsung.android.globalactions.presentation.viewmodel;

import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;

public interface ActionViewModel {
    public static enum ToggleState {
        on,
        off;

    }

    public static final int DEFAULT_LONG_PRESS_TIME = 500;

    default void dismissTipPopup() {
    }

    ActionInfo getActionInfo();

    default BitmapDrawable getIcon() {
        return null;
    }

    default int getLongPressTime() {
        return 500;
    }

    default ToggleState getState() {
        return ToggleState.off;
    }

    default String getText() {
        return null;
    }

    default boolean isAvailableShow() {
        return true;
    }

    default void onLongPress() {
    }

    void onPress();

    default void onPressSecureConfirm() {
    }

    void setActionInfo(ActionInfo actionInfo);

    default void setIcon(BitmapDrawable bitmapDrawable0) {
    }

    default void setIntent(Intent intent0) {
    }

    default void setIntentAction(int v) {
    }

    default void setState(ToggleState actionViewModel$ToggleState0) {
    }

    default void setText(String s) {
    }

    default boolean showBeforeProvisioning() {
        return false;
    }

    default void showTipPopup(View view0) {
    }

    default void updateState() {
    }
}
