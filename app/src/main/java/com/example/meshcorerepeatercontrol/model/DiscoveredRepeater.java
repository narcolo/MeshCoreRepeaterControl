package com.example.meshcorerepeatercontrol.model;

import java.util.Arrays;

public class DiscoveredRepeater {
    public static final int TYPE_CHAT = 1;
    public static final int TYPE_REPEATER = 2;
    public static final int TYPE_ROOM = 3;
    public static final int TYPE_SENSOR = 4;

    private final byte[] pubKey;
    private String name;
    private int type;
    private double snr;
    private double snrIn;
    private double distanceKm = -1; // -1 means N/A
    private double lat;
    private double lon;

    public DiscoveredRepeater(byte[] pubKey, String name, int type, double snr, double snrIn) {
        this.pubKey = pubKey != null ? pubKey.clone() : new byte[0];
        this.name = name;
        this.type = type;
        this.snr = snr;
        this.snrIn = snrIn;
    }

    public byte[] getPubKey() { return pubKey; }
    public String getName() { return name; }
    public int getType() { return type; }
    public double getSnr() { return snr; }
    public double getSnrIn() { return snrIn; }

    public double getDistanceKm() { return distanceKm; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }

    public void setName(String name) { this.name = name; }
    public void setSnr(double snr) { this.snr = snr; }
    public void setSnrIn(double snrIn) { this.snrIn = snrIn; }
    public void setDistanceKm(double distanceKm) { this.distanceKm = distanceKm; }
    public void setLatLon(double lat, double lon) { this.lat = lat; this.lon = lon; }

    public String getTypeString() {
        switch (type) {
            case TYPE_CHAT: return "Chat";
            case TYPE_REPEATER: return "Repeater";
            case TYPE_ROOM: return "Room";
            case TYPE_SENSOR: return "Sensor";
            default: return "Unknown";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiscoveredRepeater that = (DiscoveredRepeater) o;
        return Arrays.equals(pubKey, that.pubKey);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(pubKey);
    }
}
