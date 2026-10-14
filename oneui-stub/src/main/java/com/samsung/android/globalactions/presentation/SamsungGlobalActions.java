/* OneUIX compile-only stub adaptation, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only */
package com.samsung.android.globalactions.presentation;

import android.net.Uri;

import com.samsung.android.globalactions.presentation.view.ExtendableGlobalActionsView;
import com.samsung.android.globalactions.presentation.viewmodel.ActionViewModel;

public interface SamsungGlobalActions {
    void addAction(ActionViewModel actionViewModel);

    void clearActions(String arg1);

    void confirmAction(ActionViewModel actionViewModel);

    void confirmSafeMode(int arg1);

    void dismissDialog(boolean arg1);

    ExtendableGlobalActionsView getGlobalActionsView();

    default int getSideKeyType() {
        return -1;
    }

    void hideDialogOnSecureConfirm();

    boolean isActionConfirming();

    boolean isDeviceProvisioned();

    void onCancelDialog();

    void onShowDialog();

    void registerContentObserver(Uri arg1, Runnable arg2);

    void registerSecureConfirmAction(ActionViewModel arg1);

    void setDisabled();

    void setKeyguardShowing(boolean arg1);

    void setOverrideDefaultActions(boolean arg1);
}
