package com.ccs.maple.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.ccs.maple.R;
import com.ccs.maple.model.VmConfig;
import com.ccs.maple.model.VmStore;
import com.ccs.maple.qemu.QemuAssets;
import com.ccs.maple.qemu.VmService;
import com.ccs.maple.qemu.VmSession;
import com.ccs.maple.util.UtmImporter;

import java.util.ArrayList;
import java.util.List;

/** The list of virtual machines. */
public final class MainActivity extends Activity {
    private static final int REQ_UTM = 10;

    private VmStore store;
    private final List<VmConfig> vms = new ArrayList<>();
    private ArrayAdapter<VmConfig> adapter;
    private final Runnable onChange = this::reload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new VmStore(this);

        FrameLayout root = new FrameLayout(this);
        ListView list = new ListView(this);
        TextView empty = new TextView(this);
        empty.setText(R.string.empty_list);
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(16);
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        empty.setPadding(pad, pad, pad, pad);
        root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        setContentView(root);

        adapter = new ArrayAdapter<VmConfig>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, vms) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                VmConfig vm = vms.get(position);
                ((TextView) v.findViewById(android.R.id.text1)).setText(vm.name);
                ((TextView) v.findViewById(android.R.id.text2)).setText(subtitle(vm));
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> open(vms.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showItemMenu(v, vms.get(pos));
            return true;
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        VmService.addChangeListener(onChange);
        reload();
    }

    @Override
    protected void onPause() {
        VmService.removeChangeListener(onChange);
        super.onPause();
    }

    private void reload() {
        vms.clear();
        vms.addAll(store.list());
        adapter.notifyDataSetChanged();
    }

    private String subtitle(VmConfig vm) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(vm.os.labelRes)).append(" · ")
                .append(vm.isArm() ? "ARM64" : "x86-64").append(" · ")
                .append(vm.ramMb).append(" MB");
        VmSession s = VmService.get(vm.id);
        if (s != null && s.isAlive()) {
            sb.append(" · ").append(stateLabel(s.state()));
        } else if (vm.hasSuspendState) {
            sb.append(" · ").append(getString(R.string.state_suspended));
        }
        return sb.toString();
    }

    private String stateLabel(VmSession.State st) {
        switch (st) {
            case STARTING:
                return getString(R.string.state_starting);
            case RUNNING:
                return getString(R.string.state_running);
            case PAUSED:
                return getString(R.string.state_paused);
            case STOPPING:
                return getString(R.string.state_stopping);
            default:
                return getString(R.string.state_stopped);
        }
    }

    private void open(VmConfig vm) {
        if (!QemuAssets.binariesPresent(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.err_no_qemu_title)
                    .setMessage(R.string.err_no_qemu_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (!vm.hasDisk() && !vm.hasCd()) {
            Toast.makeText(this, R.string.err_nothing_to_boot, Toast.LENGTH_LONG).show();
            edit(vm);
            return;
        }
        startActivity(VmActivity.intent(this, vm.id));
    }

    private void edit(VmConfig vm) {
        startActivity(new Intent(this, EditVmActivity.class).putExtra(EditVmActivity.EXTRA_ID,
                vm.id));
    }

    private void showItemMenu(View anchor, VmConfig vm) {
        boolean running = VmService.isRunning(vm.id);
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, running ? R.string.action_open : R.string.action_start);
        if (running) {
            m.add(0, 2, 0, R.string.action_stop);
        } else {
            m.add(0, 3, 0, R.string.action_edit);
            if (vm.hasSuspendState) m.add(0, 6, 0, R.string.action_discard_state);
        }
        m.add(0, 4, 0, R.string.action_shortcut);
        if (!running) m.add(0, 5, 0, R.string.action_delete);
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    open(vm);
                    break;
                case 2: {
                    VmSession s = VmService.get(vm.id);
                    if (s != null) s.stop(true);
                    break;
                }
                case 3:
                    edit(vm);
                    break;
                case 4:
                    pinShortcut(vm);
                    break;
                case 5:
                    new AlertDialog.Builder(this)
                            .setTitle(vm.name)
                            .setMessage(R.string.confirm_delete)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.action_delete, (d, w) -> {
                                store.delete(vm.id);
                                reload();
                            })
                            .show();
                    break;
                case 6:
                    vm.hasSuspendState = false;
                    store.save(vm);
                    reload();
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    private void pinShortcut(VmConfig vm) {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, R.string.err_shortcut, Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutInfo info = new ShortcutInfo.Builder(this, "vm-" + vm.id)
                .setShortLabel(vm.name.isEmpty() ? getString(R.string.app_name) : vm.name)
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(VmActivity.intent(this, vm.id).setAction(Intent.ACTION_VIEW))
                .build();
        sm.requestPinShortcut(info, null);
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.action_new_vm).setShowAsAction(
                MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        menu.add(0, 2, 0, R.string.action_library).setShowAsAction(
                MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        menu.add(0, 3, 0, R.string.action_import_utm);
        menu.add(0, 4, 0, R.string.action_about);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                startActivity(new Intent(this, EditVmActivity.class));
                return true;
            case 2:
                startActivity(new Intent(this, LibraryActivity.class));
                return true;
            case 3:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.action_import_utm)
                        .setMessage(R.string.utm_hint)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.action_choose, (d, w) -> startActivityForResult(
                                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_UTM))
                        .show();
                return true;
            case 4:
                new AlertDialog.Builder(this)
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.about_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UTM && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            importUtm(data.getData());
        }
    }

    @SuppressWarnings("deprecation")
    private void importUtm(Uri tree) {
        ProgressDialog pd = ProgressDialog.show(this, null, getString(R.string.utm_importing),
                true, false);
        new Thread(() -> {
            String error = null;
            try {
                UtmImporter.importBundle(getApplicationContext(), tree);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String err = error;
            runOnUiThread(() -> {
                pd.dismiss();
                if (err != null) {
                    new AlertDialog.Builder(this).setTitle(R.string.action_import_utm)
                            .setMessage(err).setPositiveButton(android.R.string.ok, null).show();
                } else {
                    Toast.makeText(this, R.string.utm_imported, Toast.LENGTH_SHORT).show();
                }
                reload();
            });
        }, "utm-import").start();
    }
}
