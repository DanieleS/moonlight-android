package com.limelight.ui;

import androidx.recyclerview.widget.RecyclerView;

public interface AdapterFragmentCallbacks {
    int getAdapterFragmentLayoutId();
    void receiveRecyclerView(RecyclerView recyclerView);
}
