package com.ccs.maple.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.ccs.maple.R;
import com.ccs.maple.iso.IsoInspector;
import com.ccs.maple.model.OsType;
import com.ccs.maple.model.VmConfig;
import com.ccs.maple.model.VmStore;
import com.ccs.maple.qemu.QemuAssets;
import com.ccs.maple.qemu.QemuCommandBuilder;
import com.ccs.maple.util.SharedFolder;

import java.io.File;
import java.util.List;

/** Create or edit a virtual machine. */
public final class EditVmActivity extends Activity {
    public static final String EXTRA_ID = "vm";

    private static final int REQ_CD = 1;
    private static final int REQ_DISK = 2;
    private static final int REQ_SHARE = 3;

    private static final String[] ARCHES = {VmConfig.ARCH_X86_64, VmConfig.ARCH_AARCH64};
    private static final String[] FIRMWARES = {VmConfig.FW_BIOS, VmConfig.FW_UEFI};
    private static final String[] PROFILES = {VmConfig.PROFILE_BALANCED, VmConfig.PROFILE_MAX,
            VmConfig.PROFILE_SUSTAINED};
    private static final String[] SCALINGS = {VmConfig.SCALE_FIT, VmConfig.SCALE_STRETCH,
            VmConfig.SCALE_INTEGER};
    private static final String[] ORIENTATIONS = {VmConfig.ORIENT_AUTO, VmConfig.ORIENT_LANDSCAPE,
            VmConfig.ORIENT_PORTRAIT};
    private static final String[] INPUTS = {VmConfig.INPUT_TRACKPAD, VmConfig.INPUT_TOUCH,
            VmConfig.INPUT_MOUSE};
    private static final int[][] RESOLUTIONS = {{640, 480}, {800, 600}, {1024, 768}, {1280, 720},
            {1280, 800}, {1366, 768}, {1600, 900}, {1920, 1080}};

    private VmStore store;
    private VmConfig vm;
    private boolean isNew;
    /** Size of a blank disk to create on save; 0 = none. */
    private int pendingDiskGb;

    private LinearLayout form;
    private EditText name;
    private Spinner os;
    private Spinner arch;
    private Spinner firmware;
    private EditText ram;
    private Spinner cpus;
    private TextView diskInfo;
    private TextView cdInfo;
    private CheckBox bootCdFirst;
    private CheckBox network;
    private EditText forwards;
    private CheckBox sound;
    private TextView shareInfo;
    private CheckBox shareWritable;
    private Spinner profile;
    private Spinner resolution;
    private Spinner scaling;
    private Spinner orientation;
    private Spinner input;
    private SeekBar speed;
    private TextView speedLabel;
    private CheckBox highRefresh;
    private CheckBox suspendOnStop;
    private CheckBox win11Helper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new VmStore(this);
        String id = getIntent().getStringExtra(EXTRA_ID);
        vm = id != null ? store.get(id) : null;
        isNew = vm == null;
        if (isNew) vm = new VmConfig();
        setTitle(isNew ? R.string.action_new_vm : R.string.action_edit);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        buildForm();
        bind();
    }

    // ------------------------------------------------------------------ form construction

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void section(int title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.accent));
        t.setTextSize(14);
        t.setAllCaps(true);
        t.setPadding(0, dp(22), 0, dp(6));
        form.addView(t);
    }

    private TextView label(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, dp(8), 0, 0);
        form.addView(t);
        return t;
    }

    private TextView info() {
        TextView t = new TextView(this);
        t.setTextSize(15);
        t.setTextColor(getColor(R.color.text_primary));
        t.setPadding(0, dp(4), 0, dp(4));
        form.addView(t);
        return t;
    }

    private EditText edit(int inputType) {
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setSingleLine(true);
        form.addView(e);
        return e;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        form.addView(s);
        return s;
    }

    private CheckBox check(int text) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        form.addView(c);
        return c;
    }

    private void buttons(int[] labels, View.OnClickListener[] actions) {
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
            b.setText(labels[i]);
            b.setTextColor(getColor(R.color.accent));
            b.setOnClickListener(actions[i]);
            row.addView(b);
        }
        form.addView(row);
    }

    private String[] strings(int... ids) {
        String[] out = new String[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = getString(ids[i]);
        return out;
    }

    private void buildForm() {
        ScrollView scroll = new ScrollView(this);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(4), dp(18), dp(40));
        scroll.addView(form, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        section(R.string.sec_general);
        label(R.string.f_name);
        name = edit(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        section(R.string.sec_media);
        label(R.string.f_installer);
        cdInfo = info();
        buttons(new int[]{R.string.action_choose, R.string.action_eject},
                new View.OnClickListener[]{v -> pick(REQ_CD), v -> {
                    vm.cdPath = "";
                    vm.cdUri = "";
                    vm.cdLabel = "";
                    vm.cdIsDiskImage = false;
                    refreshMedia();
                }});
        bootCdFirst = check(R.string.f_boot_cd_first);
        label(R.string.f_disk);
        diskInfo = info();
        buttons(new int[]{R.string.action_new_disk, R.string.action_choose,
                        R.string.action_remove},
                new View.OnClickListener[]{v -> askDiskSize(), v -> pick(REQ_DISK), v -> {
                    pendingDiskGb = 0;
                    vm.diskUri = "";
                    if (isNew) vm.diskFile = "";
                    else if (!vm.diskFile.isEmpty()) confirmDetachDisk();
                    refreshMedia();
                }});

        section(R.string.sec_system);
        label(R.string.f_os);
        OsType[] types = OsType.values();
        String[] osNames = new String[types.length];
        for (int i = 0; i < types.length; i++) osNames[i] = getString(types[i].labelRes);
        os = spinner(osNames);
        label(R.string.f_arch);
        arch = spinner(new String[]{"x86-64 (PC)", "ARM64"});
        label(R.string.f_firmware);
        firmware = spinner(new String[]{"BIOS", "UEFI"});
        label(R.string.f_ram);
        ram = edit(InputType.TYPE_CLASS_NUMBER);
        label(R.string.f_cpus);
        cpus = spinner(new String[]{getString(R.string.auto), "1", "2", "3", "4", "5", "6", "7",
                "8"});
        label(R.string.f_profile);
        profile = spinner(strings(R.string.profile_balanced, R.string.profile_max,
                R.string.profile_sustained));
        win11Helper = check(R.string.f_win11_helper);
        suspendOnStop = check(R.string.f_suspend_on_stop);

        section(R.string.sec_display);
        label(R.string.f_resolution);
        String[] res = new String[RESOLUTIONS.length];
        for (int i = 0; i < res.length; i++) {
            res[i] = RESOLUTIONS[i][0] + " × " + RESOLUTIONS[i][1];
        }
        resolution = spinner(res);
        label(R.string.f_scaling);
        scaling = spinner(strings(R.string.scale_fit, R.string.scale_stretch,
                R.string.scale_integer));
        label(R.string.f_orientation);
        orientation = spinner(strings(R.string.auto, R.string.orient_landscape,
                R.string.orient_portrait));
        highRefresh = check(R.string.f_high_refresh);

        section(R.string.sec_input);
        label(R.string.f_input_mode);
        input = spinner(strings(R.string.input_trackpad, R.string.input_touch,
                R.string.input_mouse));
        speedLabel = label(R.string.f_pointer_speed);
        speed = new SeekBar(this);
        speed.setMax(375);
        form.addView(speed);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedLabel.setText(getString(R.string.f_pointer_speed) + ": " + (progress + 25)
                        + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        section(R.string.sec_network);
        network = check(R.string.f_network);
        label(R.string.f_forwards);
        forwards = edit(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        forwards.setHint("tcp:2222:22, tcp:8080:80");
        sound = check(R.string.f_sound);

        section(R.string.sec_share);
        shareInfo = info();
        buttons(new int[]{R.string.action_choose, R.string.action_remove},
                new View.OnClickListener[]{v -> pickShare(), v -> removeShare()});
        shareWritable = check(R.string.f_share_writable);
        TextView hint = label(R.string.share_hint);
        hint.setPadding(0, 0, 0, 0);

        os.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                win11Helper.setVisibility(OsType.values()[pos] == OsType.WIN_11 ? View.VISIBLE
                        : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        arch.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // ARM64 machines always boot through UEFI.
                firmware.setEnabled(pos == 0);
                if (pos == 1) firmware.setSelection(1);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    private void bind() {
        name.setText(vm.name);
        os.setSelection(vm.os.ordinal());
        arch.setSelection(indexOf(ARCHES, vm.arch));
        firmware.setSelection(vm.isUefi() ? 1 : 0);
        ram.setText(String.valueOf(vm.ramMb));
        cpus.setSelection(Math.max(0, Math.min(8, vm.cpus)));
        profile.setSelection(indexOf(PROFILES, vm.profile));
        win11Helper.setChecked(vm.win11Helper);
        suspendOnStop.setChecked(vm.suspendOnStop);
        int r = 2;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (RESOLUTIONS[i][0] == vm.displayWidth && RESOLUTIONS[i][1] == vm.displayHeight) {
                r = i;
            }
        }
        resolution.setSelection(r);
        scaling.setSelection(indexOf(SCALINGS, vm.scaling));
        orientation.setSelection(indexOf(ORIENTATIONS, vm.orientation));
        highRefresh.setChecked(vm.highRefresh);
        input.setSelection(indexOf(INPUTS, vm.inputMode));
        speed.setProgress(Math.max(0, Math.min(375, vm.pointerSpeed - 25)));
        speedLabel.setText(getString(R.string.f_pointer_speed) + ": " + vm.pointerSpeed + "%");
        network.setChecked(vm.network);
        forwards.setText(String.join(", ", vm.portForwards));
        sound.setChecked(vm.sound);
        shareWritable.setChecked(vm.sharedWritable);
        bootCdFirst.setChecked(vm.bootCdFirst);
        refreshMedia();
    }

    private void refreshMedia() {
        if (vm.hasCd()) {
            cdInfo.setText(vm.cdLabel.isEmpty() ? new File(vm.cdPath).getName() : vm.cdLabel);
        } else {
            cdInfo.setText(R.string.none);
        }
        if (pendingDiskGb > 0) {
            diskInfo.setText(getString(R.string.disk_new, pendingDiskGb));
        } else if (!vm.diskUri.isEmpty()) {
            diskInfo.setText(QemuCommandBuilder.displayName(this, Uri.parse(vm.diskUri)));
        } else if (!vm.diskFile.isEmpty()) {
            diskInfo.setText(getString(R.string.disk_internal, vm.diskFile));
        } else {
            diskInfo.setText(R.string.none);
        }
        shareInfo.setText(vm.sharedUri.isEmpty() ? getString(R.string.none)
                : SharedFolder.displayName(this, Uri.parse(vm.sharedUri)));
    }

    // ------------------------------------------------------------------ media pickers

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, req);
    }

    private void askDiskSize() {
        EditText size = new EditText(this);
        size.setInputType(InputType.TYPE_CLASS_NUMBER);
        size.setText(String.valueOf(pendingDiskGb > 0 ? pendingDiskGb : 16));
        new AlertDialog.Builder(this)
                .setTitle(R.string.disk_size_title)
                .setMessage(R.string.disk_size_text)
                .setView(size)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        int gb = Integer.parseInt(size.getText().toString().trim());
                        if (gb < 1 || gb > 2048) throw new NumberFormatException();
                        pendingDiskGb = gb;
                        vm.diskUri = "";
                        refreshMedia();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.err_disk_size, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void confirmDetachDisk() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.confirm_delete_disk)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    new File(store.dir(vm), vm.diskFile).delete();
                    vm.diskFile = "";
                    vm.hasSuspendState = false;
                    refreshMedia();
                })
                .show();
    }

    private void pickShare() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SHARE);
    }

    private void removeShare() {
        if (!vm.sharedUri.isEmpty()) {
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(vm.sharedUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // already gone
            }
        }
        vm.sharedUri = "";
        refreshMedia();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_SHARE) {
            // Read access lets the VM see the folder; write access lets guest changes be
            // copied back. Both survive restarts because the grant is persisted.
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                vm.sharedUri = uri.toString();
            } catch (SecurityException e) {
                Toast.makeText(this, R.string.err_share_path, Toast.LENGTH_LONG).show();
            }
            refreshMedia();
            return;
        }
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (requestCode == REQ_DISK) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e2) {
                Toast.makeText(this, R.string.err_no_persist, Toast.LENGTH_LONG).show();
            }
        }
        String display = QemuCommandBuilder.displayName(this, uri);
        if (requestCode == REQ_DISK) {
            vm.diskUri = uri.toString();
            pendingDiskGb = 0;
            refreshMedia();
            return;
        }
        // Installer image: look inside to pick the right virtual hardware.
        vm.cdUri = uri.toString();
        vm.cdPath = "";
        vm.cdLabel = display;
        ProgressDialog pd = ProgressDialog.show(this, null, getString(R.string.inspecting), true);
        new Thread(() -> {
            IsoInspector.Info info = IsoInspector.inspect(getApplicationContext(), uri, display);
            runOnUiThread(() -> {
                pd.dismiss();
                applyInspection(info, display);
            });
        }, "iso-inspect").start();
    }

    private void applyInspection(IsoInspector.Info info, String display) {
        vm.cdIsDiskImage = !info.isIso;
        os.setSelection(info.os.ordinal());
        arch.setSelection(indexOf(ARCHES, info.arch));
        firmware.setSelection(VmConfig.FW_UEFI.equals(info.firmware) ? 1 : 0);
        if (isNew) {
            ram.setText(String.valueOf(info.ramMb));
            if (name.getText().toString().trim().isEmpty()) {
                String n = display;
                int dot = n.lastIndexOf('.');
                if (dot > 0) n = n.substring(0, dot);
                name.setText(n.length() > 40 ? n.substring(0, 40) : n);
            }
            if (!vm.hasDisk() && pendingDiskGb == 0) pendingDiskGb = info.diskGb;
            if (info.os == OsType.WIN_11) win11Helper.setChecked(true);
        }
        refreshMedia();
        Toast.makeText(this, getString(R.string.detected, getString(info.os.labelRes),
                VmConfig.ARCH_AARCH64.equals(info.arch) ? "ARM64" : "x86-64",
                VmConfig.FW_UEFI.equals(info.firmware) ? "UEFI" : "BIOS"),
                Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ save

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.action_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS
                | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == 1) {
            save();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void save() {
        String n = name.getText().toString().trim();
        if (n.isEmpty()) {
            name.setError(getString(R.string.err_name));
            name.requestFocus();
            return;
        }
        int ramMb;
        try {
            ramMb = Integer.parseInt(ram.getText().toString().trim());
        } catch (NumberFormatException e) {
            ramMb = 0;
        }
        if (ramMb < 16 || ramMb > 16384) {
            ram.setError(getString(R.string.err_ram));
            ram.requestFocus();
            return;
        }
        List<String> fwd = VmConfig.parsePortForwards(forwards.getText().toString());
        if (fwd == null) {
            forwards.setError(getString(R.string.err_forwards));
            forwards.requestFocus();
            return;
        }
        vm.name = n;
        vm.os = OsType.values()[os.getSelectedItemPosition()];
        vm.arch = ARCHES[arch.getSelectedItemPosition()];
        vm.firmware = vm.isArm() ? VmConfig.FW_UEFI
                : FIRMWARES[firmware.getSelectedItemPosition()];
        vm.ramMb = ramMb;
        vm.cpus = cpus.getSelectedItemPosition();
        vm.profile = PROFILES[profile.getSelectedItemPosition()];
        vm.win11Helper = win11Helper.isChecked() && vm.os == OsType.WIN_11;
        vm.suspendOnStop = suspendOnStop.isChecked();
        int[] res = RESOLUTIONS[resolution.getSelectedItemPosition()];
        vm.displayWidth = res[0];
        vm.displayHeight = res[1];
        vm.scaling = SCALINGS[scaling.getSelectedItemPosition()];
        vm.orientation = ORIENTATIONS[orientation.getSelectedItemPosition()];
        vm.highRefresh = highRefresh.isChecked();
        vm.inputMode = INPUTS[input.getSelectedItemPosition()];
        vm.pointerSpeed = speed.getProgress() + 25;
        vm.network = network.isChecked();
        vm.portForwards.clear();
        vm.portForwards.addAll(fwd);
        vm.sound = sound.isChecked();
        vm.sharedWritable = shareWritable.isChecked();
        vm.bootCdFirst = bootCdFirst.isChecked();
        // The virtual hardware may have changed, so an old suspended state no longer fits.
        vm.hasSuspendState = false;

        if (pendingDiskGb > 0) {
            if (!QemuAssets.imgBinary(this).isFile()) {
                new AlertDialog.Builder(this).setTitle(R.string.err_no_qemu_title)
                        .setMessage(R.string.err_no_qemu_text)
                        .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            final int gb = pendingDiskGb;
            ProgressDialog pd = ProgressDialog.show(this, null,
                    getString(R.string.creating_disk), true);
            new Thread(() -> {
                String error = null;
                try {
                    File dir = store.dir(vm);
                    File disk = new File(dir, "disk.qcow2");
                    if (disk.exists() && !disk.delete()) throw new java.io.IOException("busy");
                    QemuAssets.createQcow2(getApplicationContext(), disk, gb);
                    vm.diskFile = "disk.qcow2";
                    vm.diskUri = "";
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String err = error;
                runOnUiThread(() -> {
                    pd.dismiss();
                    if (err != null) {
                        new AlertDialog.Builder(this).setMessage(err)
                                .setPositiveButton(android.R.string.ok, null).show();
                    } else {
                        pendingDiskGb = 0;
                        store.save(vm);
                        finish();
                    }
                });
            }, "disk-create").start();
            return;
        }
        store.save(vm);
        finish();
    }
}
