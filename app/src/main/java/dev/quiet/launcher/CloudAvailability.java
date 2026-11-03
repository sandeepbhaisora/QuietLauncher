package dev.quiet.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Does not attempt a cloud request when Android reports no usable internet. */
final class CloudAvailability {
    static boolean online(Context context) {
        ConnectivityManager connectivity =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) return false;
        try {
            Network active = connectivity.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(active);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (SecurityException ex) { return false; }
    }
    private CloudAvailability() {}
}
