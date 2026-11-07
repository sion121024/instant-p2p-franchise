package kfc.udp.client.anticheat;

/** 마인크래프트 AABB와 같은 축 정렬 상자 — 엔진이 마크 클래스를 쓰지 않으려고 따로 둔다. */
public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public static Box ofPlayer(double x, double y, double z, double width, double height) {
        double h = width / 2;
        return new Box(x - h, y, z - h, x + h, y + height, z + h);
    }

    public Box inflate(double d) {
        return new Box(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
    }

    public Box lerp(Box o, double t) {
        return new Box(minX + (o.minX - minX) * t, minY + (o.minY - minY) * t, minZ + (o.minZ - minZ) * t,
                maxX + (o.maxX - maxX) * t, maxY + (o.maxY - maxY) * t, maxZ + (o.maxZ - maxZ) * t);
    }

    /** 점에서 상자까지 가장 가까운 거리(안에 있으면 0). */
    public double distanceTo(double x, double y, double z) {
        double dx = Math.max(Math.max(minX - x, 0), x - maxX);
        double dy = Math.max(Math.max(minY - y, 0), y - maxY);
        double dz = Math.max(Math.max(minZ - z, 0), z - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double centerX() { return (minX + maxX) / 2; }
    public double centerY() { return (minY + maxY) / 2; }
    public double centerZ() { return (minZ + maxZ) / 2; }

    /**
     * 반직선(원점 o, 방향 d 단위벡터)이 상자에 닿는지 — 닿으면 진입 거리, 아니면 -1. 원점이 안에 있으면 0.
     * slab 방식.
     */
    public double rayHit(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist) {
        double tMin = 0, tMax = maxDist;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz};
        double[] lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) return -1;
            } else {
                double t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return -1;
            }
        }
        return tMin;
    }

    /** 마크 시선 벡터 — yaw/pitch(도) → 단위 방향. Entity.calculateViewVector와 같은 식. */
    public static double[] look(float yaw, float pitch) {
        double p = Math.toRadians(pitch), y = Math.toRadians(-yaw);
        double cp = Math.cos(p);
        return new double[]{Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp};
    }

    /** 두 단위벡터 사이 각도(도). */
    public static double angle(double[] a, double[] b) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        double la = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        double lb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        if (la < 1e-9 || lb < 1e-9) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot / (la * lb)))));
    }
}
