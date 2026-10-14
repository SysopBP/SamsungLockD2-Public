/* OneUIX compile-only stub adaptation, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only */
package com.samsung.android.globalactions.presentation.viewmodel;

@SuppressWarnings("unused")
public class ActionInfo {
    private String mDescription;
    private int mIconResId;
    private String mLabel;
    private String mName;
    private String mStateLabel;
    private int mViewIndex;
    private ViewType mViewType;

    public ActionInfo() {
        this.mName = "";
        this.mIconResId = -1;
        this.mViewType = ViewType.CENTER_ICON_1P_VIEW;
        this.mLabel = "";
        this.mDescription = "";
        this.mViewIndex = -1;
        this.mStateLabel = "";
    }

    public String getDescription() {
        return this.mDescription;
    }

    public int getIcon() {
        return this.mIconResId;
    }

    public String getLabel() {
        return this.mLabel;
    }

    public String getName() {
        return this.mName;
    }

    public String getStateLabel() {
        return this.mStateLabel;
    }

    public int getViewIndex() {
        return this.mViewIndex;
    }

    public ViewType getViewType() {
        return this.mViewType;
    }

    public void setDescription(String s) {
        this.mDescription = s;
    }

    public void setIcon(int resId) {
        this.mIconResId = resId;
    }

    public void setLabel(String s) {
        this.mLabel = s;
    }

    public void setName(String s) {
        this.mName = s;
    }

    public void setStateLabel(String s) {
        this.mStateLabel = s;
    }

    public void setViewIndex(int v) {
        this.mViewIndex = v;
    }

    public void setViewType(ViewType viewType0) {
        this.mViewType = viewType0;
    }
}
