package com.ccs.maple.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ccs.maple.R;
import com.ccs.maple.library.CatalogEntry;
import com.ccs.maple.library.Downloader;
import com.ccs.maple.qemu.QemuAssets;
import com.ccs.maple.util.Io;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** OS library: free operating systems downloaded from their official mirrors. */
public final class LibraryActivity extends Activity {
    private List<CatalogEntry> entries;
    private BaseAdapter adapter;
    private final Set<String> announced = new HashSet<>();
    private final Runnable onProgress = () -> {
        adapter.notifyDataSetChanged();
        announce();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.action_library);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        entries = CatalogEntry.load(this);
        ListView list = new ListView(this);
        setContentView(list);
        final float d = getResources().getDisplayMetrics().density;

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return entries.size();
            }

            @Override
            public Object getItem(int position) {
                return entries.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = (LinearLayout) convertView;
                if (row == null) {
                    row = new LinearLayout(LibraryActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    int p = (int) (14 * d);
                    row.setPadding(p, p, p, p);
                    TextView title = new TextView(LibraryActivity.this);
                    title.setTextSize(17);
                    title.setTextColor(getColor(R.color.text_primary));
                    TextView sub = new TextView(LibraryActivity.this);
                    sub.setTextSize(13);
                    TextView status = new TextView(LibraryActivity.this);
                    status.setTextSize(13);
                    status.setTextColor(getColor(R.color.accent));
                    ProgressBar bar = new ProgressBar(LibraryActivity.this, null,
                            android.R.attr.progressBarStyleHorizontal);
                    bar.setMax(1000);
                    row.addView(title);
                    row.addView(sub);
                    row.addView(status);
                    row.addView(bar, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                CatalogEntry e = entries.get(position);
                ((TextView) row.getChildAt(0)).setText(e.title());
                ((TextView) row.getChildAt(1)).setText((e.isArm() ? "ARM64" : "x86-64") + " · "
                        + Io.humanSize(e.sizeBytes) + " · " + e.description);
                TextView status = (TextView) row.getChildAt(2);
                ProgressBar bar = (ProgressBar) row.getChildAt(3);
                Downloader.Task t = Downloader.task(e.id);
                if (t == null) {
                    boolean have = Downloader.imageFile(LibraryActivity.this, e).isFile();
                    status.setText(have ? R.string.lib_downloaded : R.string.lib_tap_to_download);
                    bar.setVisibility(View.GONE);
                } else {
                    status.setText(statusText(t));
                    boolean active = t.active();
                    bar.setVisibility(active ? View.VISIBLE : View.GONE);
                    bar.setIndeterminate(active && t.total <= 0);
                    if (t.total > 0) bar.setProgress((int) (t.done * 1000 / t.total));
                }
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onEntry(entries.get(pos)));
    }

    private String statusText(Downloader.Task t) {
        switch (t.phase) {
            case DOWNLOADING:
                return getString(R.string.lib_downloading, Io.humanSize(t.done),
                        Io.humanSize(t.total));
            case VERIFYING:
                return getString(R.string.lib_verifying);
            case UNPACKING:
                return getString(R.string.lib_unpacking);
            case CREATING:
                return getString(R.string.lib_creating);
            case DONE:
                return getString(R.string.lib_done);
            case CANCELLED:
                return getString(R.string.lib_cancelled);
            default:
                return getString(R.string.lib_failed, t.error);
        }
    }

    private void onEntry(CatalogEntry e) {
        Downloader.Task t = Downloader.task(e.id);
        if (t != null && t.active()) {
            new AlertDialog.Builder(this)
                    .setTitle(e.title())
                    .setMessage(R.string.lib_cancel_question)
                    .setNegativeButton(R.string.action_keep, null)
                    .setPositiveButton(R.string.action_cancel_download,
                            (d, w) -> Downloader.cancel(e.id))
                    .show();
            return;
        }
        if (!QemuAssets.imgBinary(this).isFile()) {
            new AlertDialog.Builder(this).setTitle(R.string.err_no_qemu_title)
                    .setMessage(R.string.err_no_qemu_text)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        boolean have = Downloader.imageFile(this, e).isFile();
        String msg = e.description + "\n\n" + getString(have ? R.string.lib_confirm_reuse
                : R.string.lib_confirm, Io.humanSize(e.sizeBytes), e.url)
                + "\n\n" + e.checksumAlgo.toUpperCase() + ": " + e.checksumValue;
        new AlertDialog.Builder(this)
                .setTitle(e.title())
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(have ? R.string.action_create_vm : R.string.action_download,
                        (d, w) -> {
                            announced.remove(e.id);
                            Downloader.start(this, e);
                        })
                .show();
    }

    private void announce() {
        for (CatalogEntry e : entries) {
            Downloader.Task t = Downloader.task(e.id);
            if (t != null && t.phase == Downloader.Phase.DONE && announced.add(e.id)) {
                Toast.makeText(this, getString(R.string.lib_vm_created, e.name),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Downloads finished while the screen was closed are not announced again.
        for (CatalogEntry e : entries) {
            Downloader.Task t = Downloader.task(e.id);
            if (t != null && t.phase == Downloader.Phase.DONE) announced.add(e.id);
        }
        Downloader.addListener(onProgress);
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        Downloader.removeListener(onProgress);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
