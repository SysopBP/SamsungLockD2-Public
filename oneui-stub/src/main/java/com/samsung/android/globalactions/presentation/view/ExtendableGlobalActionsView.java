/* OneUIX compile-only stub adaptation, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only */
package com.samsung.android.globalactions.presentation.view;

import com.samsung.android.globalactions.presentation.strategies.WindowDecorationStrategy;
import com.samsung.android.globalactions.presentation.viewmodel.ActionViewModel;

public interface ExtendableGlobalActionsView {
    void addWindowDecorator(WindowDecorationStrategy windowDecorationStrategy);

    void cancelConfirming();

    void dismiss();

    void dismissWithAnimation();

    void forceRequestLayout();

    boolean getCoverSecureConfirmState();

    void hideDialogOnSecureConfirm();

    void notifyDataSetChanged();

    default void setCoverSecureConfirmState(boolean z) {
    }

    void showActionConfirming(ActionViewModel actionViewModel);

    void updateViewList();
}
