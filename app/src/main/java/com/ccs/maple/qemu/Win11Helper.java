package com.ccs.maple.qemu;

/**
 * Answer file placed on a small virtual USB drive. Windows Setup reads autounattend.xml from the
 * root of removable media and runs the commands below before it checks the hardware, which
 * switches off the TPM, Secure Boot, RAM, CPU and storage requirements of Windows 11.
 */
final class Win11Helper {
    private Win11Helper() {
    }

    private static final String[] LAB_CONFIG = {
            "BypassTPMCheck", "BypassSecureBootCheck", "BypassRAMCheck", "BypassCPUCheck",
            "BypassStorageCheck"
    };

    static String autounattend(boolean arm) {
        String arch = arm ? "arm64" : "amd64";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n");
        sb.append("<unattend xmlns=\"urn:schemas-microsoft-com:unattend\" ")
                .append("xmlns:wcm=\"http://schemas.microsoft.com/WMIConfig/2002/State\">\r\n");
        sb.append("  <settings pass=\"windowsPE\">\r\n");
        sb.append(component("Microsoft-Windows-Setup", arch));
        sb.append("      <RunSynchronous>\r\n");
        int order = 1;
        for (String key : LAB_CONFIG) {
            sb.append(command(order++, "reg add HKLM\\SYSTEM\\Setup\\LabConfig /v " + key
                    + " /t REG_DWORD /d 1 /f"));
        }
        sb.append("      </RunSynchronous>\r\n");
        sb.append("    </component>\r\n");
        sb.append("  </settings>\r\n");
        sb.append("  <settings pass=\"specialize\">\r\n");
        sb.append(component("Microsoft-Windows-Deployment", arch));
        sb.append("      <RunSynchronous>\r\n");
        // Lets the first-run wizard finish without a network connection or online account.
        sb.append(command(1, "reg add HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE "
                + "/v BypassNRO /t REG_DWORD /d 1 /f"));
        sb.append("      </RunSynchronous>\r\n");
        sb.append("    </component>\r\n");
        sb.append("  </settings>\r\n");
        sb.append("</unattend>\r\n");
        return sb.toString();
    }

    private static String component(String name, String arch) {
        return "    <component name=\"" + name + "\" processorArchitecture=\"" + arch
                + "\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" "
                + "versionScope=\"nonSxS\">\r\n";
    }

    private static String command(int order, String path) {
        return "        <RunSynchronousCommand wcm:action=\"add\">\r\n"
                + "          <Order>" + order + "</Order>\r\n"
                + "          <Path>" + path + "</Path>\r\n"
                + "        </RunSynchronousCommand>\r\n";
    }
}
