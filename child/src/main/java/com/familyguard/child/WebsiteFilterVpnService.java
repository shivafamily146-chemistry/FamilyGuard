package com.familyguard.child;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.content.pm.ServiceInfo;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;

public class WebsiteFilterVpnService extends VpnService {
    private static final String CHANNEL = "familyguard_web_filter";
    private static final String VIRTUAL_DNS = "10.111.0.1";
    private ParcelFileDescriptor tunnel;
    private Thread worker;
    private volatile boolean running;
    private FirebaseFirestore db;
    private ListenerRegistration rulesListener;
    private final Set<String> blockedDomains = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService dnsExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Long> lastLogged = new HashMap<>();
    private String childUid;
    private String parentUid;

    @Override public void onCreate() {
        super.onCreate();
        db = FirebaseFirestore.getInstance();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startVisibleNotification();
        if (tunnel == null) {
            try {
                Builder builder = new Builder()
                    .setSession("FamilyGuard website filtering")
                    .setMtu(1500)
                    .addAddress("10.111.0.2", 32)
                    .addRoute(VIRTUAL_DNS, 32)
                    .addDnsServer(VIRTUAL_DNS);
                tunnel = builder.establish();
                if (tunnel == null) { stopSelf(); return START_NOT_STICKY; }
                listenForRules();
                running = true;
                worker = new Thread(this::readPackets, "FamilyGuard-DNS-filter");
                worker.start();
            } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        }
        return START_STICKY;
    }

    private void startVisibleNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Website filtering", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("FamilyGuard website filtering active")
            .setContentText("DNS domain rules are being applied on this device.")
            .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(3002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(3002, n);
    }

    private void listenForRules() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) return;
        childUid = auth.getCurrentUser().getUid();
        db.collection("familyLinks").document(childUid).get().addOnSuccessListener(link -> { if (link.exists()) parentUid = link.getString("parentUid"); });
        rulesListener = db.collection("websiteBlockRules").whereEqualTo("childUid", childUid)
            .addSnapshotListener((snap, error) -> {
                if (error != null || snap == null) return;
                synchronized (blockedDomains) {
                    blockedDomains.clear();
                    for (DocumentSnapshot d : snap) {
                        String domain = d.getString("domain");
                        if (Boolean.TRUE.equals(d.getBoolean("active")) && domain != null) {
                            String normalized = normalizeDomain(domain);
                            if (!normalized.isEmpty()) blockedDomains.add(normalized);
                        }
                    }
                }
            });
    }

    private String normalizeDomain(String input) {
        String d = input.trim().toLowerCase(Locale.US);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        if (d.startsWith("https://")) d = d.substring(8);
        if (d.startsWith("http://")) d = d.substring(7);
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        if (d.startsWith("www.")) d = d.substring(4);
        return d;
    }

    private boolean isBlocked(String domain) {
        synchronized (blockedDomains) {
            for (String rule : blockedDomains) {
                if (domain.equals(rule) || domain.endsWith("." + rule)) return true;
            }
        }
        return false;
    }

    private void readPackets() {
        byte[] buffer = new byte[32767];
        try (FileInputStream in = new FileInputStream(tunnel.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tunnel.getFileDescriptor())) {
            while (running) {
                int length = in.read(buffer);
                if (length <= 0) continue;
                byte[] packet = new byte[length];
                System.arraycopy(buffer, 0, packet, 0, length);
                processDnsPacket(packet, out);
            }
        } catch (Exception ignored) { }
    }

    private void processDnsPacket(byte[] packet, FileOutputStream out) {
        try {
            if (packet.length < 28 || (packet[0] >> 4 & 0x0F) != 4 || (packet[9] & 0xFF) != 17) return;
            int ipHeader = (packet[0] & 0x0F) * 4;
            if (packet.length < ipHeader + 8) return;
            int dstPort = ((packet[ipHeader + 2] & 255) << 8) | (packet[ipHeader + 3] & 255);
            if (dstPort != 53) return;
            int dnsStart = ipHeader + 8;
            int dnsLength = packet.length - dnsStart;
            if (dnsLength < 17) return;
            String domain = readDomain(packet, dnsStart, dnsLength);
            if (domain == null) return;
            boolean block = isBlocked(domain);
            logDomainVisit(domain, block);
            byte[] dnsResponse = block ? blockedResponse(packet, dnsStart, dnsLength)
                : forwardDns(packet, ipHeader, dnsStart, dnsLength);
            if (dnsResponse == null) return;
            byte[] response = buildIpv4UdpResponse(packet, ipHeader, dnsResponse);
            if (response != null) synchronized (out) { out.write(response); out.flush(); }
        } catch (Exception ignored) { }
    }

    private String readDomain(byte[] p, int start, int length) {
        try {
            int pos = start + 12, end = start + length;
            StringBuilder name = new StringBuilder();
            while (pos < end) {
                int n = p[pos++] & 255;
                if (n == 0) break;
                if ((n & 0xC0) != 0 || n > 63 || pos + n > end) return null;
                if (name.length() > 0) name.append('.');
                name.append(new String(p, pos, n, StandardCharsets.US_ASCII));
                pos += n;
            }
            return name.length() == 0 ? null : name.toString().toLowerCase(Locale.US);
        } catch (Exception e) { return null; }
    }

    private byte[] blockedResponse(byte[] packet, int start, int length) {
        byte[] dns = new byte[length];
        System.arraycopy(packet, start, dns, 0, length);
        dns[2] = (byte) ((dns[2] & 0x7F) | 0x80);
        dns[3] = (byte) ((dns[3] & 0xF0) | 0x83);
        for (int i = 6; i < 12; i++) dns[i] = 0;
        return dns;
    }

    private byte[] forwardDns(byte[] packet, int ipHeader, int start, int length) {
        try (DatagramSocket socket = new DatagramSocket()) {
            if (!protect(socket)) return blockedResponse(packet, start, length);
            socket.setSoTimeout(2500);
            byte[] query = new byte[length];
            System.arraycopy(packet, start, query, 0, length);
            DatagramPacket request = new DatagramPacket(query, query.length, InetAddress.getByName("1.1.1.1"), 53);
            socket.send(request);
            byte[] answer = new byte[4096];
            DatagramPacket reply = new DatagramPacket(answer, answer.length);
            socket.receive(reply);
            byte[] result = new byte[reply.getLength()];
            System.arraycopy(reply.getData(), 0, result, 0, result.length);
            return result;
        } catch (Exception e) { return blockedResponse(packet, start, length); }
    }

    private void logDomainVisit(String domain, boolean blocked) {
        if (childUid == null || parentUid == null || domain == null || domain.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (lastLogged) {
            Long previous = lastLogged.get(domain);
            if (previous != null && now - previous < 600000L) return;
            lastLogged.put(domain, now);
        }
        Map<String, Object> event = new HashMap<>();
        event.put("childUid", childUid);
        event.put("parentUid", parentUid);
        event.put("domain", domain);
        event.put("blocked", blocked);
        event.put("queriedAt", FieldValue.serverTimestamp());
        db.collection("browserDomainEvents").add(event);
    }

    private byte[] buildIpv4UdpResponse(byte[] request, int ipHeader, byte[] dns) {
        if (dns.length > 60000) return null;
        int total = ipHeader + 8 + dns.length;
        byte[] p = new byte[total];
        System.arraycopy(request, 0, p, 0, ipHeader);
        for (int i = 0; i < 4; i++) {
            p[12 + i] = request[16 + i];
            p[16 + i] = request[12 + i];
        }
        p[2] = (byte) (total >> 8); p[3] = (byte) total;
        p[8] = 64; p[10] = 0; p[11] = 0;
        int srcPort = ((request[ipHeader] & 255) << 8) | (request[ipHeader + 1] & 255);
        p[ipHeader] = 0; p[ipHeader + 1] = 53;
        p[ipHeader + 2] = (byte) (srcPort >> 8); p[ipHeader + 3] = (byte) srcPort;
        int udpLen = 8 + dns.length;
        p[ipHeader + 4] = (byte) (udpLen >> 8); p[ipHeader + 5] = (byte) udpLen;
        p[ipHeader + 6] = 0; p[ipHeader + 7] = 0;
        System.arraycopy(dns, 0, p, ipHeader + 8, dns.length);
        int sum = 0;
        for (int i = 0; i < ipHeader; i += 2) sum += ((p[i] & 255) << 8) | (p[i + 1] & 255);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        int checksum = (~sum) & 0xFFFF;
        p[10] = (byte) (checksum >> 8); p[11] = (byte) checksum;
        return p;
    }

    @Override public void onRevoke() { stopSelf(); }

    @Override public void onDestroy() {
        running = false;
        if (rulesListener != null) rulesListener.remove();
        if (tunnel != null) try { tunnel.close(); } catch (Exception ignored) { }
        dnsExecutor.shutdownNow();
        super.onDestroy();
    }
}
