import android.content.AttributionSource;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Field;

/** Development AVD helper, executed as adb shell; never packaged in MoaTrip. */
public final class EthernetKeepalive {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        // app_process creates a system Context whose default attribution is
        // "android". Attribute the request to the real shell UID/package.
        // This grants no permissions: ConnectivityService checks UID 2000's
        // existing CONNECTIVITY_USE_RESTRICTED_NETWORKS permission.
        Field contextField = ConnectivityManager.class.getDeclaredField("mContext");
        contextField.setAccessible(true);
        Context managerContext = (Context) contextField.get(manager);
        Field attribution = managerContext.getClass().getDeclaredField("mAttributionSource");
        attribution.setAccessible(true);
        attribution.set(managerContext, new AttributionSource.Builder(Process.myUid())
                .setPackageName("com.android.shell").build());

        // Connect 2.1.0's eth0 advertises TRUSTED, but none of these default
        // capabilities. A matching request lets Android start its DHCP client.
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .removeCapability(NetworkCapabilities.class.getField("NET_CAPABILITY_NOT_VCN_MANAGED").getInt(null))
                .build();
        manager.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                System.out.println("Ethernet available: " + network);
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                System.out.println("Ethernet addresses: " + properties.getLinkAddresses());
            }
        });
        System.out.println("Ethernet requested as adb shell");
        Looper.loop();
    }
}
