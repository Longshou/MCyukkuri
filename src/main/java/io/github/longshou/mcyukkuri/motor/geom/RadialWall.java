package io.github.longshou.mcyukkuri.motor.geom;

import java.util.ArrayList;
import java.util.List;

/** Inner radius of a round mesh at a horizontal section, including the chords between authored vertices. */
public record RadialWall(List<Vec[]> triangles) {
    public static RadialWall read(String file, String partName) {
        var source = GeometryJson.read(file);
        var vertices = source.field("vertices").getAsJsonArray();
        var triangles = new ArrayList<Vec[]>();
        for (var value : source.field("parts").getAsJsonArray()) {
            var part = value.getAsJsonObject();
            if (!part.get("name").getAsString().equals(partName)) continue;
            int start = part.get("start").getAsInt(), end = start + part.get("count").getAsInt();
            for (int i = start; i < end; i += 3) {
                var triangle = new Vec[3];
                for (int j = 0; j < 3; j++) triangle[j] = new Vec(vertices.get((i+j)*8).getAsDouble(),
                        vertices.get((i+j)*8+1).getAsDouble(), vertices.get((i+j)*8+2).getAsDouble());
                triangles.add(triangle);
            }
        }
        source.check(!triangles.isEmpty(), "parts", "missing " + partName);
        return new RadialWall(List.copyOf(triangles));
    }
    public double radius(double y) {
        double radius = Double.POSITIVE_INFINITY;
        for (var triangle : triangles) {
            Vec first = null;
            for (int i = 0; i < 3; i++) {
                var a = triangle[i]; var b = triangle[(i+1)%3];
                if (a.y() == b.y() || y < Math.min(a.y(), b.y()) || y > Math.max(a.y(), b.y())) continue;
                var at = a.add(b.sub(a).scale((y-a.y())/(b.y()-a.y())));
                radius = Math.min(radius, at.hlen());
                if (first != null) {
                    var d = at.sub(first);
                    double length = d.x()*d.x() + d.z()*d.z();
                    if (length > 0) radius = Math.min(radius, first.add(d.scale(Math.clamp(
                            -(first.x()*d.x()+first.z()*d.z())/length, 0, 1))).hlen());
                }
                first = at;
            }
        }
        return radius;
    }
}
