package packets.packetcapture.sniff;

/**
 * No network interface could be opened because the process may not capture packets. On Linux, libpcap needs the
 * CAP_NET_RAW and CAP_NET_ADMIN capabilities (or root).
 */
public class CapturePermissionException extends Exception {
    public CapturePermissionException(String message) {
        super(message);
    }
}
