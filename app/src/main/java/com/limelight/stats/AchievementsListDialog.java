package com.limelight.stats;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.nvstream.http.Achievement;
import com.limelight.nvstream.http.GameAchievements;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every achievement of one game, CouchPilot's achievements sheet: how far along the game is,
 * a filter for all, unlocked and still to do, and the list as the host sorted it — the unlocked
 * ones first, newest first, then what is left.
 *
 * <p>Hidden achievements stay hidden until asked for: the game didn't want them spoiled, so a
 * hidden one that is still locked shows only that it exists, until its row is selected with A
 * (or a tap). Rarity is labelled only for the bands that deserve a word.
 */
public final class AchievementsListDialog {
    private static final float HEIGHT_FRACTION = 0.9f;

    private enum Filter { ALL, UNLOCKED, LOCKED }

    private final Activity activity;
    private final HostSession session;
    private final GameAchievements data;
    private final StatsText text;
    private final Set<String> revealed = new HashSet<>();
    private final List<Achievement> shown = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private Filter filter = Filter.ALL;

    private TextView[] filterButtons;
    private RecyclerView list;
    private TextView empty;

    private AchievementsListDialog(Activity activity, HostSession session, GameAchievements data) {
        this.activity = activity;
        this.session = session;
        this.data = data;
        this.text = new StatsText(activity);
    }

    public static Dialog show(Activity activity, HostSession session, String gameName, GameAchievements data) {
        return new AchievementsListDialog(activity, session, data).show(gameName);
    }

    private Dialog show(String gameName) {
        Dialog dialog = new Dialog(activity, R.style.MenuSheetDialog);
        View panel = LayoutInflater.from(dialog.getContext()).inflate(R.layout.achievements_list, null, false);
        dialog.setContentView(panel);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            int cap = activity.getResources().getDimensionPixelSize(R.dimen.stats_list_max_width);
            lp.width = Math.min(cap, (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f));
            lp.height = (int) (activity.getResources().getDisplayMetrics().heightPixels * HEIGHT_FRACTION);
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
            window.setDimAmount(0.6f);
        }

        ((TextView) panel.findViewById(R.id.achievementsTitle))
                .setText(activity.getString(R.string.achievements_title, gameName));

        long[] score = data.getGamerScore();
        ((TextView) panel.findViewById(R.id.achievementsCount)).setText(score == null
                ? activity.getString(R.string.achievements_count, data.getUnlocked(), data.getTotal())
                : activity.getString(R.string.achievements_count_score, data.getUnlocked(), data.getTotal(),
                        (int) score[0], (int) score[1]));
        View meter = panel.findViewById(R.id.achievementsMeter);
        meter.setPivotX(0);
        meter.setScaleX(data.getPercent() / 100f);
        ((TextView) panel.findViewById(R.id.achievementsPercent)).setText(activity.getString(
                data.getPercent() == 100 ? R.string.achievements_percent_all : R.string.achievements_percent,
                data.getPercent()));

        TextView foot = panel.findViewById(R.id.achievementsFoot);
        if (data.getLastRefresh() != null) {
            foot.setText(activity.getString(R.string.achievements_refreshed,
                    text.unlockedLabel(data.getLastRefresh(), true)));
        } else {
            foot.setVisibility(View.GONE);
        }

        filterButtons = new TextView[]{
                panel.findViewById(R.id.achievementsFilterAll),
                panel.findViewById(R.id.achievementsFilterUnlocked),
                panel.findViewById(R.id.achievementsFilterLocked)};
        filterButtons[1].setText(activity.getString(R.string.achievements_filter_unlocked, data.getUnlocked()));
        filterButtons[2].setText(activity.getString(R.string.achievements_filter_locked,
                Math.max(0, data.getTotal() - data.getUnlocked())));
        Filter[] filters = Filter.values();
        for (int i = 0; i < filterButtons.length; i++) {
            final Filter f = filters[i];
            filterButtons[i].setOnClickListener(v -> setFilter(f));
        }

        list = panel.findViewById(R.id.achievementsList);
        list.setLayoutManager(new LinearLayoutManager(activity));
        // Revealing a row changes it in place; an animation would drop the focus mid-change.
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        empty = panel.findViewById(R.id.achievementsEmpty);

        applyFilter();
        dialog.show();

        // Straight onto the list, where the reading is; the filter is one press up.
        list.post(() -> {
            RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
            if (first != null) {
                first.itemView.requestFocus();
            } else {
                filterButtons[0].requestFocus();
            }
        });
        return dialog;
    }

    private void setFilter(Filter f) {
        if (f == filter) {
            return;
        }
        filter = f;
        applyFilter();
        list.scrollToPosition(0);
    }

    private void applyFilter() {
        for (int i = 0; i < filterButtons.length; i++) {
            filterButtons[i].setSelected(Filter.values()[i] == filter);
        }
        shown.clear();
        for (Achievement a : data.getItems()) {
            if (filter == Filter.ALL || a.isUnlocked() == (filter == Filter.UNLOCKED)) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();

        boolean none = shown.isEmpty();
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none) {
            empty.setText(filter == Filter.LOCKED ? R.string.achievements_all_done : R.string.achievements_none_yet);
        }
    }

    private boolean isMasked(Achievement a) {
        return a.isHidden() && !a.isUnlocked() && !revealed.contains(a.getId());
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.achievement_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(shown.get(position));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final View lock;
        private final TextView name;
        private final TextView description;
        private final TextView when;
        private final TextView rarity;
        private final TextView meta;

        Holder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.achievementIcon);
            icon.setClipToOutline(true);
            lock = itemView.findViewById(R.id.achievementLock);
            name = itemView.findViewById(R.id.achievementName);
            description = itemView.findViewById(R.id.achievementDescription);
            when = itemView.findViewById(R.id.achievementWhen);
            rarity = itemView.findViewById(R.id.achievementRarity);
            meta = itemView.findViewById(R.id.achievementMeta);
        }

        void bind(Achievement a) {
            AchievementIcons.load(icon, a, session);
            itemView.setAlpha(a.isUnlocked() ? 1f : 0.75f);
            lock.setVisibility(a.isUnlocked() ? View.GONE : View.VISIBLE);

            boolean masked = isMasked(a);
            if (masked) {
                name.setText(R.string.achievements_hidden);
                name.setTextColor(ContextCompat.getColor(activity, R.color.content_muted));
                description.setText(R.string.achievements_reveal);
                description.setTextColor(ContextCompat.getColor(activity, R.color.signal));
                description.setVisibility(View.VISIBLE);
            } else {
                name.setText(a.getName());
                name.setTextColor(ContextCompat.getColor(activity, R.color.content_primary));
                description.setText(a.getDescription());
                description.setTextColor(ContextCompat.getColor(activity, R.color.content_muted));
                description.setVisibility(a.getDescription().isEmpty() ? View.GONE : View.VISIBLE);
            }

            if (a.isUnlocked()) {
                when.setVisibility(View.VISIBLE);
                when.setText(text.unlockedLabel(a.getUnlockedAt(), false));
            } else {
                when.setVisibility(View.GONE);
            }

            StatsFormat.Rarity band = StatsFormat.rarity(a.getPercent());
            if (band == null) {
                rarity.setVisibility(View.GONE);
            } else {
                rarity.setVisibility(View.VISIBLE);
                boolean ultra = band == StatsFormat.Rarity.ULTRA_RARE;
                rarity.setText(band == StatsFormat.Rarity.ULTRA_RARE ? R.string.achievements_rarity_ultra_rare
                        : band == StatsFormat.Rarity.RARE ? R.string.achievements_rarity_rare
                        : R.string.achievements_rarity_uncommon);
                rarity.setBackgroundResource(ultra ? R.drawable.stats_chip_rare_bg : R.drawable.stats_chip_bg);
                rarity.setTextColor(ContextCompat.getColor(activity, ultra ? R.color.danger : R.color.signal));
            }

            StringBuilder line = new StringBuilder();
            if (a.getPercent() != null) {
                line.append(activity.getString(R.string.achievements_of_players,
                        StatsFormat.percent(a.getPercent(), text.getLocale())));
            }
            if (a.getGamerScore() != null && a.getGamerScore() > 0) {
                if (line.length() > 0) {
                    line.append(" · ");
                }
                line.append(activity.getString(R.string.achievements_gamerscore, (int) Math.round(a.getGamerScore())));
            }
            meta.setText(line);

            itemView.setOnClickListener(v -> {
                if (isMasked(a)) {
                    revealed.add(a.getId());
                    int position = getBindingAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        adapter.notifyItemChanged(position);
                    }
                }
            });
        }
    }
}
