package app.danielthemaniel.patches.googlemaps.extension;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Builds the animated Ditto navigation vehicle from the Pokémon X/Y Ditto model archive
 * (The Models Resource asset 296368: Ditto/pm0132_00.dae + Ditto/Textures/pm0132_00_*.png), so no Nintendo
 * asset has to ship with the patches.
 *
 * Output (into a folder, the same layout as "Custom navigation vehicle" bundles):
 *   model.glb, texture.png, texture_dark.png, frames/frames.properties, frames/&lt;clip&gt;/&lt;i&gt;.glb
 *
 * Steps, mirroring the Blender pipeline the hand-made version came from:
 *  1. Parse the COLLADA file: the three meshes (body, eyes, mouth), their skins and the joint hierarchy.
 *  2. Recolour the textures from pale X/Y Ditto to a proper purple and pack them into one 512 px atlas
 *     (body top-left, eyes top-right, mouth bottom-left).
 *  3. Pose the skeleton for every frame of six procedural clips (idle, drive, turnleft, turnright, start,
 *     arrive), skin the mesh, normalise it (1.25 units tall, feet at 0, facing +X) and write one .glb per
 *     frame laid out like Google's gltf/car.glb, which Maps' map engine draws as a static mesh.
 *
 * Pure Java apart from {@link Images}, so it runs on the device (Bitmap) and on a desktop (ImageIO).
 */
public final class DittoBuilder {
    private DittoBuilder() {}

    /** Image decode/encode, provided by the platform. Pixels are ARGB ints, row-major. */
    public interface Images {
        int[] decode(byte[] png, int[] sizeOut) throws IOException; // sizeOut = {width, height}

        byte[] encodePng(int[] argb, int width, int height) throws IOException;
    }

    private static final double TARGET_HEIGHT = 1.25;
    private static final double SOURCE_UNIT = 34.4;   // loc keys are in 1/34.4 of the normalised height
    private static final double GLTF_SCALE = 0.01;    // raw mesh in metres under a root node scaled 0.01

    // ---------------------------------------------------------------- entry point

    /** Reads the X/Y archive and writes the vehicle folder. Returns a short summary. */
    public static String build(InputStream archive, File out, Images images) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (base.equals("pm0132_00.dae") || (name.toLowerCase(Locale.ROOT).contains("textures/")
                    && (base.equals("pm0132_00_body1.png") || base.equals("pm0132_00_eye1.png")
                    || base.equals("pm0132_00_mouth1.png")))) {
                    files.put(base, readAll(zip));
                }
            }
        }
        for (String need : new String[]{"pm0132_00.dae", "pm0132_00_body1.png", "pm0132_00_eye1.png", "pm0132_00_mouth1.png"}) {
            if (!files.containsKey(need)) throw new IOException("archive has no " + need);
        }

        Model model = parse(files.get("pm0132_00.dae"));
        byte[] atlas = atlas(images, files.get("pm0132_00_body1.png"), files.get("pm0132_00_eye1.png"),
            files.get("pm0132_00_mouth1.png"));
        byte[] tiny = tinyTexture(images, atlas);

        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("mkdirs " + out);
        write(new File(out, "texture.png"), atlas);
        write(new File(out, "texture_dark.png"), atlas);
        write(new File(out, "model.glb"), glb(model, model.pose(null, 0), tiny));

        // Picker icons, like Google's (156 / 312 / 468 px, 3/4 front view, transparent background).
        int[] atlasSize = new int[2];
        int[] atlasPx = images.decode(atlas, atlasSize);
        double[][] rest = model.pose(null, 0);
        String[] iconNames = {"icon.png", "icon@2x.png", "icon@3x.png"};
        int[] iconSizes = {156, 312, 468};
        for (int i = 0; i < 3; i++) {
            int[] px = renderIcon(model, rest, atlasPx, atlasSize[0], atlasSize[1], iconSizes[i]);
            write(new File(out, iconNames[i]), images.encodePng(px, iconSizes[i], iconSizes[i]));
        }

        File framesDir = new File(out, "frames");
        StringBuilder props = new StringBuilder();
        int total = 0;
        for (Clip clip : CLIPS) {
            File dir = new File(framesDir, clip.name);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdirs " + dir);
            List<Integer> frames = new ArrayList<>();
            for (int f = 0; clip.loop ? f < clip.frames : f <= clip.frames; f += clip.step) frames.add(f);
            if (!clip.loop && frames.get(frames.size() - 1) != clip.frames) frames.add(clip.frames);
            for (int i = 0; i < frames.size(); i++) {
                double t = frames.get(i) / (double) clip.frames;
                write(new File(dir, i + ".glb"), glb(model, model.pose(clip, t), tiny));
            }
            props.append(clip.name).append('=').append(frames.size()).append(',')
                .append(fmt(30.0 / clip.step)).append(',').append(clip.loop ? "loop" : "once").append('\n');
            total += frames.size();
        }
        write(new File(framesDir, "frames.properties"), props.toString().getBytes(StandardCharsets.UTF_8));
        return total + " frames, " + model.vertexCount() + " vertices";
    }

    // ---------------------------------------------------------------- COLLADA

    /** One rendered corner-vertex: rest position/normal (DAE world), atlas UV, skin influences. */
    private static final class Model {
        double[] pos;      // 3 per vertex, rest pose, DAE world (Z up)
        double[] nrm;      // 3 per vertex
        float[] uv;        // 2 per vertex, glTF convention (v down)
        int[][] joints;    // per vertex, joint indices into names
        double[][] weights;
        int[] indices;
        String[] names;    // joints
        int[] parent;      // joint parent index or -1
        double[][] head;   // joint rest position, DAE world
        double scale;      // normalisation
        double zMin;

        int vertexCount() {
            return pos.length / 3;
        }

        /** Posed positions + normals in glTF space for clip at t (null clip = rest). */
        double[][] pose(Clip clip, double t) {
            int n = names.length;
            double[][] rest = new double[n][];
            double[][] restInv = new double[n][];
            for (int j = 0; j < n; j++) {
                rest[j] = mul(translation(head[j][0], head[j][1], head[j][2]), ROT_X_90);
                restInv[j] = invertRigid(rest[j]);
            }
            Map<String, Channels> keys = clip == null ? new HashMap<>() : clip.tracks.at(t);
            double unit = (TARGET_HEIGHT / SOURCE_UNIT) / scale; // loc key unit in DAE units
            double[][] world = new double[n][];
            double[][] skin = new double[n][];
            for (int j = 0; j < n; j++) world[j] = null;
            for (int pass = 0; pass < n; pass++) {
                for (int j = 0; j < n; j++) {
                    if (world[j] != null) continue;
                    int p = parent[j];
                    if (p >= 0 && world[p] == null) continue;
                    Channels c = keys.get(names[j]);
                    double[] basis = c == null ? identity() : c.basis(unit);
                    double[] local = p >= 0 ? mul(restInv[p], rest[j]) : rest[j];
                    world[j] = p >= 0 ? mul(mul(world[p], local), basis) : mul(local, basis);
                }
            }
            for (int j = 0; j < n; j++) skin[j] = mul(world[j], restInv[j]);

            int vc = vertexCount();
            double[] outP = new double[vc * 3];
            double[] outN = new double[vc * 3];
            for (int v = 0; v < vc; v++) {
                double px = 0, py = 0, pz = 0, nx = 0, ny = 0, nz = 0;
                double x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
                double a = nrm[v * 3], b = nrm[v * 3 + 1], c = nrm[v * 3 + 2];
                for (int k = 0; k < joints[v].length; k++) {
                    double w = weights[v][k];
                    double[] m = skin[joints[v][k]];
                    px += w * (m[0] * x + m[1] * y + m[2] * z + m[3]);
                    py += w * (m[4] * x + m[5] * y + m[6] * z + m[7]);
                    pz += w * (m[8] * x + m[9] * y + m[10] * z + m[11]);
                    nx += w * (m[0] * a + m[1] * b + m[2] * c);
                    ny += w * (m[4] * a + m[5] * b + m[6] * c);
                    nz += w * (m[8] * a + m[9] * b + m[10] * c);
                }
                // Normalise (height 1.25, feet at 0), face +X (rotate +90 deg about Z), then Blender Z-up -> glTF
                // Y-up and the 0.01 root scale: (x, y, z) -> 0.01 * (-y, z, -x).
                double qx = px * scale, qy = py * scale, qz = pz * scale - zMin * scale;
                outP[v * 3] = -qy * GLTF_SCALE;
                outP[v * 3 + 1] = qz * GLTF_SCALE;
                outP[v * 3 + 2] = -qx * GLTF_SCALE;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1e-12) len = 1;
                outN[v * 3] = -ny / len;
                outN[v * 3 + 1] = nz / len;
                outN[v * 3 + 2] = -nx / len;
            }
            return new double[][]{outP, outN};
        }
    }

    private static Model parse(byte[] dae) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(dae));

        // Sources: id -> floats (with stride) or names.
        Map<String, double[]> floats = new HashMap<>();
        Map<String, Integer> strides = new HashMap<>();
        Map<String, String[]> nameArrays = new HashMap<>();
        for (Element s : elements(doc.getDocumentElement(), "source")) {
            String id = s.getAttribute("id");
            Element fa = first(s, "float_array");
            if (fa != null) floats.put(id, doubles(fa.getTextContent()));
            Element na = first(s, "Name_array");
            if (na != null) nameArrays.put(id, na.getTextContent().trim().split("\\s+"));
            Element acc = first(s, "accessor");
            strides.put(id, acc != null && !acc.getAttribute("stride").isEmpty() ? Integer.parseInt(acc.getAttribute("stride")) : 1);
        }

        // Joint hierarchy (visual scene).
        Map<String, double[]> jointWorld = new LinkedHashMap<>();
        Map<String, String> jointParent = new HashMap<>();
        Element scene = first(doc.getDocumentElement(), "visual_scene");
        for (Element n : children(scene, "node")) walk(n, identity(), null, jointWorld, jointParent);

        String[] names = jointWorld.keySet().toArray(new String[0]);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < names.length; i++) index.put(names[i], i);

        // Skins by geometry id.
        Map<String, Element> skins = new HashMap<>();
        for (Element c : elements(doc.getDocumentElement(), "controller")) {
            Element skin = first(c, "skin");
            if (skin != null) skins.put(skin.getAttribute("source").replace("#", ""), skin);
        }

        List<double[]> pos = new ArrayList<>(), nrm = new ArrayList<>();
        List<float[]> uv = new ArrayList<>();
        List<int[]> vj = new ArrayList<>();
        List<double[]> vw = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();

        for (Element g : elements(doc.getDocumentElement(), "geometry")) {
            Element mesh = first(g, "mesh");
            Element skin = skins.get(g.getAttribute("id"));
            if (mesh == null || skin == null) continue;
            // Skin data.
            double[] bsm = doubles(first(skin, "bind_shape_matrix").getTextContent());
            String[] skinJoints = null;
            double[] invBind = null, weightValues = null;
            Element jointsEl = first(skin, "joints");
            for (Element in : children(jointsEl, "input")) {
                String src = in.getAttribute("source").replace("#", "");
                if (in.getAttribute("semantic").equals("JOINT")) skinJoints = nameArrays.get(src);
                if (in.getAttribute("semantic").equals("INV_BIND_MATRIX")) invBind = floats.get(src);
            }
            Element vwEl = first(skin, "vertex_weights");
            int jOff = 0, wOff = 1, inputs = 0;
            for (Element in : children(vwEl, "input")) {
                inputs++;
                int off = Integer.parseInt(in.getAttribute("offset"));
                if (in.getAttribute("semantic").equals("JOINT")) jOff = off;
                if (in.getAttribute("semantic").equals("WEIGHT")) {
                    wOff = off;
                    weightValues = floats.get(in.getAttribute("source").replace("#", ""));
                }
            }
            int[] vcount = ints(first(vwEl, "vcount").getTextContent());
            int[] v = ints(first(vwEl, "v").getTextContent());
            int[][] infJ = new int[vcount.length][];
            double[][] infW = new double[vcount.length][];
            for (int i = 0, at = 0; i < vcount.length; i++) {
                infJ[i] = new int[vcount[i]];
                infW[i] = new double[vcount[i]];
                double sum = 0;
                for (int k = 0; k < vcount[i]; k++, at += inputs) {
                    String jn = skinJoints[v[at + jOff]];
                    Integer ji = index.get(jn);
                    if (ji == null) throw new IOException("unknown joint " + jn);
                    infJ[i][k] = ji;
                    infW[i][k] = weightValues[v[at + wOff]];
                    sum += infW[i][k];
                }
                if (sum > 0) for (int k = 0; k < vcount[i]; k++) infW[i][k] /= sum;
            }
            // Per-joint skin matrix at rest: jointWorld * invBind * bindShape.
            double[][] restSkin = new double[skinJoints.length][];
            int[] skinToGlobal = new int[skinJoints.length];
            for (int k = 0; k < skinJoints.length; k++) {
                skinToGlobal[k] = index.get(skinJoints[k]);
                double[] ib = new double[16];
                System.arraycopy(invBind, k * 16, ib, 0, 16);
                restSkin[k] = mul(mul(jointWorld.get(skinJoints[k]), ib), bsm);
            }

            // Geometry sources.
            String vertsPos = null;
            Element vertices = first(mesh, "vertices");
            for (Element in : children(vertices, "input")) {
                if (in.getAttribute("semantic").equals("POSITION")) vertsPos = in.getAttribute("source").replace("#", "");
            }
            double[] positions = floats.get(vertsPos);
            for (String primTag : new String[]{"polylist", "triangles"}) {
                for (Element prim : children(mesh, primTag)) {
                    String material = prim.getAttribute("material").toLowerCase(Locale.ROOT);
                    double cu = 0.0, cv = 0.5; // body: top-left
                    if (material.startsWith("eye")) {
                        cu = 0.5;
                        cv = 0.5;
                    } else if (material.startsWith("mouth")) {
                        cu = 0.0;
                        cv = 0.0;
                    }
                    int stride = 0, offV = 0, offN = -1, offT = -1;
                    String srcN = null, srcT = null;
                    for (Element in : children(prim, "input")) {
                        int off = Integer.parseInt(in.getAttribute("offset"));
                        stride = Math.max(stride, off + 1);
                        String sem = in.getAttribute("semantic");
                        String src = in.getAttribute("source").replace("#", "");
                        if (sem.equals("VERTEX")) offV = off;
                        if (sem.equals("NORMAL")) {
                            offN = off;
                            srcN = src;
                        }
                        if (sem.equals("TEXCOORD") && offT < 0) {
                            offT = off;
                            srcT = src;
                        }
                    }
                    double[] normals = srcN == null ? null : floats.get(srcN);
                    double[] tex = srcT == null ? null : floats.get(srcT);
                    int tStride = srcT == null ? 2 : strides.get(srcT);
                    int[] p = ints(first(prim, "p").getTextContent());
                    Element vc = first(prim, "vcount");
                    int[] counts;
                    if (vc != null) {
                        counts = ints(vc.getTextContent());
                    } else {
                        counts = new int[p.length / stride / 3];
                        java.util.Arrays.fill(counts, 3);
                    }
                    Map<String, Integer> corners = new HashMap<>();
                    for (int poly = 0, at = 0; poly < counts.length; poly++) {
                        int[] ring = new int[counts[poly]];
                        for (int k = 0; k < counts[poly]; k++, at += stride) {
                            int pi = p[at + offV];
                            int ni = offN >= 0 ? p[at + offN] : -1;
                            int ti = offT >= 0 ? p[at + offT] : -1;
                            // Corners share a vertex when position, normal and UV values match (the DAE gives
                            // every corner its own UV index).
                            String key = pi + "/" + valueKey(normals, ni, 3) + "/" + valueKey(tex, ti, tStride);
                            Integer out = corners.get(key);
                            if (out == null) {
                                out = pos.size();
                                corners.put(key, out);
                                // Rest world position by skinning at rest.
                                double x = positions[pi * 3], y = positions[pi * 3 + 1], z = positions[pi * 3 + 2];
                                double[] wp = new double[3];
                                double[] wn = new double[3];
                                double a = 0, b = 0, c = 1;
                                if (normals != null) {
                                    a = normals[ni * 3];
                                    b = normals[ni * 3 + 1];
                                    c = normals[ni * 3 + 2];
                                }
                                int[] gj = new int[infJ[pi].length];
                                for (int q = 0; q < infJ[pi].length; q++) {
                                    // infJ holds global joint indices; find this skin's matrix for it.
                                    int global = infJ[pi][q];
                                    double[] m = null;
                                    for (int s = 0; s < skinToGlobal.length; s++) if (skinToGlobal[s] == global) m = restSkin[s];
                                    double w = infW[pi][q];
                                    wp[0] += w * (m[0] * x + m[1] * y + m[2] * z + m[3]);
                                    wp[1] += w * (m[4] * x + m[5] * y + m[6] * z + m[7]);
                                    wp[2] += w * (m[8] * x + m[9] * y + m[10] * z + m[11]);
                                    wn[0] += w * (m[0] * a + m[1] * b + m[2] * c);
                                    wn[1] += w * (m[4] * a + m[5] * b + m[6] * c);
                                    wn[2] += w * (m[8] * a + m[9] * b + m[10] * c);
                                    gj[q] = global;
                                }
                                pos.add(wp);
                                nrm.add(wn);
                                double u = 0, vv = 0;
                                if (tex != null) {
                                    u = tex[ti * tStride];
                                    vv = tex[ti * tStride + 1];
                                }
                                double fu = Math.min(Math.max(u, 0.0), 1.0);
                                double fv = Math.min(Math.max(vv > 1.0 ? vv - Math.floor(vv) : vv, 0.0), 1.0);
                                // Atlas cell in Blender UV space (v up), then glTF flips v.
                                uv.add(new float[]{(float) (cu + 0.5 * fu), (float) (1.0 - (cv + 0.5 * fv))});
                                vj.add(gj);
                                vw.add(infW[pi].clone());
                            }
                            ring[k] = out;
                        }
                        for (int k = 1; k + 1 < ring.length; k++) {
                            idx.add(ring[0]);
                            idx.add(ring[k]);
                            idx.add(ring[k + 1]);
                        }
                    }
                }
            }
        }
        if (pos.isEmpty()) throw new IOException("no skinned meshes in the DAE");

        Model m = new Model();
        int vc = pos.size();
        m.pos = new double[vc * 3];
        m.nrm = new double[vc * 3];
        m.uv = new float[vc * 2];
        m.joints = vj.toArray(new int[0][]);
        m.weights = vw.toArray(new double[0][]);
        double zMin = Double.MAX_VALUE, zMax = -Double.MAX_VALUE;
        for (int i = 0; i < vc; i++) {
            System.arraycopy(pos.get(i), 0, m.pos, i * 3, 3);
            System.arraycopy(nrm.get(i), 0, m.nrm, i * 3, 3);
            m.uv[i * 2] = uv.get(i)[0];
            m.uv[i * 2 + 1] = uv.get(i)[1];
            zMin = Math.min(zMin, m.pos[i * 3 + 2]);
            zMax = Math.max(zMax, m.pos[i * 3 + 2]);
        }
        m.indices = new int[idx.size()];
        for (int i = 0; i < idx.size(); i++) m.indices[i] = idx.get(i);
        m.names = names;
        m.parent = new int[names.length];
        m.head = new double[names.length][];
        for (int i = 0; i < names.length; i++) {
            String p = jointParent.get(names[i]);
            m.parent[i] = p == null ? -1 : index.get(p);
            double[] w = jointWorld.get(names[i]);
            m.head[i] = new double[]{w[3], w[7], w[11]};
        }
        m.scale = TARGET_HEIGHT / (zMax - zMin);
        m.zMin = zMin;
        return m;
    }

    private static void walk(Element node, double[] parentWorld, String parentJoint,
                             Map<String, double[]> jointWorld, Map<String, String> jointParent) {
        double[] local = identity();
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element)) continue;
            Element e = (Element) c;
            String tag = localName(e);
            if (tag.equals("matrix")) {
                local = mul(local, doubles(e.getTextContent()));
            } else if (tag.equals("translate")) {
                double[] t = doubles(e.getTextContent());
                local = mul(local, translation(t[0], t[1], t[2]));
            } else if (tag.equals("rotate")) {
                double[] r = doubles(e.getTextContent());
                local = mul(local, axisAngle(r[0], r[1], r[2], Math.toRadians(r[3])));
            } else if (tag.equals("scale")) {
                double[] s = doubles(e.getTextContent());
                local = mul(local, scaling(s[0], s[1], s[2]));
            }
        }
        double[] world = mul(parentWorld, local);
        String joint = parentJoint;
        if ("JOINT".equals(node.getAttribute("type"))) {
            String sid = node.getAttribute("sid");
            String name = sid.isEmpty() ? node.getAttribute("name") : sid;
            jointWorld.put(name, world);
            if (!node.getAttribute("name").isEmpty() && !node.getAttribute("name").equals(name)) {
                jointWorld.put(node.getAttribute("name"), world); // alias
            }
            if (parentJoint != null) jointParent.put(name, parentJoint);
            joint = name;
        }
        for (Element c : children(node, "node")) walk(c, world, joint, jointWorld, jointParent);
    }

    // ---------------------------------------------------------------- clips

    /** Per-bone pose channels: Euler XYZ degrees, location (source units, bone space), scale. */
    private static final class Channels {
        double rx, ry, rz, lx, ly, lz, sx = 1, sy = 1, sz = 1;

        double[] basis(double unit) {
            // Blender: T(loc) * Rz * Ry * Rx (Euler XYZ) * S
            double[] r = mul(mul(rotZ(Math.toRadians(rz)), rotY(Math.toRadians(ry))), rotX(Math.toRadians(rx)));
            return mul(mul(translation(lx * unit, ly * unit, lz * unit), r), scaling(sx, sy, sz));
        }
    }

    private interface Tracks {
        Map<String, Channels> at(double t);
    }

    private static final class Clip {
        final String name;
        final int frames, step;
        final boolean loop;
        final Tracks tracks;

        Clip(String name, int frames, int step, boolean loop, Tracks tracks) {
            this.name = name;
            this.frames = frames;
            this.step = step;
            this.loop = loop;
            this.tracks = tracks;
        }
    }

    private static double S(double t, double k, double ph) {
        return Math.sin(2 * Math.PI * (k * t + ph));
    }

    private static double C(double t) {
        return Math.cos(2 * Math.PI * t);
    }

    private static double hop(double t) {
        return 0.5 - 0.5 * C(t);
    }

    private static Channels ch(Map<String, Channels> m, String bone) {
        Channels c = m.get(bone);
        if (c == null) m.put(bone, c = new Channels());
        return c;
    }

    private static void squash(Channels c, double t) {
        double h = hop(t);
        c.sx = c.sz = 1 + 0.06 * (1 - h) - 0.03 * h;
        c.sy = 1 - 0.10 * (1 - h) + 0.06 * h;
    }

    private static final String[] FEET = {"LFootA", "LFootB", "LFootC", "RFootA", "RFootB", "RFootC"};

    private static Map<String, Channels> drive(double t, double lean) {
        Map<String, Channels> m = new HashMap<>();
        Channels w = ch(m, "Waist");
        w.ly = 3.0 * hop(t);
        squash(w, t);
        w.rx = 6 + 2 * S(t, 1, 0);
        w.rz = lean;
        Channels s = ch(m, "Spine");
        s.rx = 3 * S(t, 1, 0.25);
        s.rz = lean * 0.6;
        Channels ha = ch(m, "HeadA");
        ha.rx = -4 * S(t, 1, 0.3);
        ha.rz = lean * 0.3;
        ch(m, "HeadB").rz = -8 * S(t, 1, 0.4);
        ch(m, "HeadC").rz = 8 * S(t, 1, 0.45);
        Channels la = ch(m, "LArm");
        la.rx = -14 + 6 * S(t, 1, 0.35);
        la.rz = -6 * S(t, 1, 0.35) + (lean < 0 ? -18 : 0);
        Channels ra = ch(m, "RArm");
        ra.rx = -14 + 6 * S(t, 1, 0.35);
        ra.rz = 6 * S(t, 1, 0.35) + (lean > 0 ? 18 : 0);
        for (int i = 0; i < FEET.length; i++) ch(m, FEET[i]).rx = 10 * S(t, 1, i / 6.0);
        return m;
    }

    private static final Clip[] CLIPS = {
        new Clip("drive", 20, 2, true, t -> drive(t, 0)),
        new Clip("idle", 60, 5, true, t -> {
            Map<String, Channels> m = new HashMap<>();
            double h = hop(t);
            Channels s = ch(m, "Spine");
            s.sx = s.sz = 1 - 0.015 * h;
            s.sy = 1 + 0.04 * h;
            Channels w = ch(m, "Waist");
            w.sx = w.sz = 1 + 0.01 * h;
            w.sy = 1 - 0.01 * h;
            Channels ha = ch(m, "HeadA");
            ha.rx = 2 * S(t, 1, 0);
            ha.rz = 1.5 * S(t, 1, 0.25);
            ch(m, "HeadB").rz = -5 * S(t, 1, 0.1);
            ch(m, "HeadC").rz = 5 * S(t, 1, 0.2);
            ch(m, "LArm").rz = -8 * S(t, 1, 0.3);
            ch(m, "RArm").rz = 8 * S(t, 1, 0.55);
            ch(m, "LHand").rz = -6 * S(t, 1, 0.45);
            ch(m, "RHand").rz = 6 * S(t, 1, 0.7);
            return m;
        }),
        new Clip("turnleft", 20, 2, true, t -> drive(t, -14)),
        new Clip("turnright", 20, 2, true, t -> drive(t, 14)),
        new Clip("start", 30, 3, false, t -> {
            Map<String, Channels> m = new HashMap<>();
            double s = Math.sin(Math.PI * t), sign = t < 0.35 ? 1 : -1;
            Channels w = ch(m, "Waist");
            w.sx = w.sz = 1 + 0.08 * s * sign;
            w.sy = 1 - 0.12 * s * sign;
            ch(m, "Spine").sy = 1 + 0.15 * s * s;
            ch(m, "LArm").rz = -25 * s;
            ch(m, "RArm").rz = 25 * s;
            return m;
        }),
        new Clip("arrive", 45, 3, false, t -> {
            Map<String, Channels> m = new HashMap<>();
            Channels w = ch(m, "Waist");
            w.ly = 5.0 * Math.abs(Math.sin(2 * Math.PI * t));
            squash(w, (2 * t) % 1);
            ch(m, "Spine").rz = 4 * S(t, 2, 0);
            ch(m, "LArm").rz = -30 - 20 * S(t, 3, 0);
            ch(m, "LHand").rz = -20 * S(t, 3, 0.2);
            ch(m, "RArm").rz = 10 * S(t, 2, 0);
            return m;
        }),
    };

    // ---------------------------------------------------------------- textures

    /** Recolours pale X/Y Ditto purple (hue 0.79, stronger saturation) and packs the 512 px atlas. */
    private static byte[] atlas(Images images, byte[] body, byte[] eye, byte[] mouth) throws IOException {
        int size = 512, cell = 256;
        int[] out = new int[size * size];
        java.util.Arrays.fill(out, 0xff000000);
        byte[][] parts = {body, eye, mouth};
        int[][] origin = {{0, 0}, {cell, 0}, {0, cell}};
        for (int p = 0; p < 3; p++) {
            int[] wh = new int[2];
            int[] px = images.decode(parts[p], wh);
            for (int y = 0; y < cell; y++) {
                for (int x = 0; x < cell; x++) {
                    int sx = x * wh[0] / cell, sy = y * wh[1] / cell; // nearest resize to 256
                    out[(origin[p][1] + y) * size + origin[p][0] + x] = recolour(px[sy * wh[0] + sx]);
                }
            }
        }
        return images.encodePng(out, size, size);
    }

    private static int recolour(int argb) {
        int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        double max = Math.max(r, Math.max(g, b)) / 255.0, min = Math.min(r, Math.min(g, b)) / 255.0;
        double v = max, s = max == 0 ? 0 : (max - min) / max;
        if (s < 0.03 || max == min) return argb | 0xff000000;
        double h;
        double rf = r / 255.0, gf = g / 255.0, bf = b / 255.0, d = max - min;
        if (max == rf) h = ((gf - bf) / d) / 6.0;
        else if (max == gf) h = ((bf - rf) / d + 2) / 6.0;
        else h = ((rf - gf) / d + 4) / 6.0;
        if (h < 0) h += 1;
        if (h < 0.6 || h > 0.95) return argb | 0xff000000;
        h = 0.79;
        s = Math.min(1.0, 0.078 + 2.22 * s);
        v = v * 0.967;
        // HSV -> RGB
        double hh = h * 6, c = v * s, xx = c * (1 - Math.abs(hh % 2 - 1)), m = v - c;
        double r1, g1, b1;
        int sector = (int) Math.floor(hh) % 6;
        switch (sector) {
            case 0: r1 = c; g1 = xx; b1 = 0; break;
            case 1: r1 = xx; g1 = c; b1 = 0; break;
            case 2: r1 = 0; g1 = c; b1 = xx; break;
            case 3: r1 = 0; g1 = xx; b1 = c; break;
            case 4: r1 = xx; g1 = 0; b1 = c; break;
            default: r1 = c; g1 = 0; b1 = xx; break;
        }
        int R = (int) Math.round((r1 + m) * 255), G = (int) Math.round((g1 + m) * 255), B = (int) Math.round((b1 + m) * 255);
        return 0xff000000 | (R << 16) | (G << 8) | B;
    }

    /** 16 px copy of the atlas for the image embedded in each .glb (Maps textures from texture.png). */
    private static byte[] tinyTexture(Images images, byte[] atlasPng) throws IOException {
        int[] wh = new int[2];
        int[] px = images.decode(atlasPng, wh);
        int n = 16;
        int[] out = new int[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) out[y * n + x] = px[(y * wh[1] / n) * wh[0] + x * wh[0] / n];
        return images.encodePng(out, n, n);
    }

    // ---------------------------------------------------------------- icon

    /** Software-rasterises the posed mesh (orthographic, 2x supersampled, textured, soft Lambert). */
    private static int[] renderIcon(Model m, double[][] posed, int[] tex, int tw, int th, int size) {
        double[] p = posed[0], n = posed[1];
        int vc = m.vertexCount();
        // Camera from the front (+X) and Ditto's left (-Z), a little above; front ends up on the left.
        double[] d = norm(new double[]{0.78, 0.42, -0.62});
        double[] up = {0, 1, 0};
        double[] f = {-d[0], -d[1], -d[2]};
        double[] r = norm(cross(f, up));
        if (r[0] > 0) { // keep the face on the image's left, like Google's icons
            d = norm(new double[]{0.78, 0.42, 0.62});
            f = new double[]{-d[0], -d[1], -d[2]};
            r = norm(cross(f, up));
        }
        double[] u = cross(r, f);
        double[] light = norm(new double[]{d[0] + 0.2, d[1] + 0.8, d[2] - 0.3});

        double[] sx = new double[vc], sy = new double[vc], sz = new double[vc];
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int v = 0; v < vc; v++) {
            double x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
            sx[v] = x * r[0] + y * r[1] + z * r[2];
            sy[v] = x * u[0] + y * u[1] + z * u[2];
            sz[v] = x * d[0] + y * d[1] + z * d[2];
            minX = Math.min(minX, sx[v]);
            maxX = Math.max(maxX, sx[v]);
            minY = Math.min(minY, sy[v]);
            maxY = Math.max(maxY, sy[v]);
        }
        int ss = 2, w = size * ss;
        double extent = Math.max(maxX - minX, maxY - minY);
        double k = 0.80 * w / extent;
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        for (int v = 0; v < vc; v++) {
            sx[v] = w / 2.0 + (sx[v] - cx) * k;
            sy[v] = w * 0.53 - (sy[v] - cy) * k; // image y grows down; sit a touch low like Google's
        }
        float[] depth = new float[w * w];
        java.util.Arrays.fill(depth, -Float.MAX_VALUE);
        int[] color = new int[w * w];
        int[] idx = m.indices;
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t], b = idx[t + 1], c = idx[t + 2];
            double area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a]);
            if (Math.abs(area) < 1e-9) continue;
            int x0 = (int) Math.max(0, Math.floor(Math.min(sx[a], Math.min(sx[b], sx[c]))));
            int x1 = (int) Math.min(w - 1, Math.ceil(Math.max(sx[a], Math.max(sx[b], sx[c]))));
            int y0 = (int) Math.max(0, Math.floor(Math.min(sy[a], Math.min(sy[b], sy[c]))));
            int y1 = (int) Math.min(w - 1, Math.ceil(Math.max(sy[a], Math.max(sy[b], sy[c]))));
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    double px = x + 0.5, py = y + 0.5;
                    double wa = ((sx[b] - px) * (sy[c] - py) - (sx[c] - px) * (sy[b] - py)) / area;
                    double wb = ((sx[c] - px) * (sy[a] - py) - (sx[a] - px) * (sy[c] - py)) / area;
                    double wc = 1 - wa - wb;
                    if (wa < 0 || wb < 0 || wc < 0) continue;
                    float z = (float) (wa * sz[a] + wb * sz[b] + wc * sz[c]);
                    int o = y * w + x;
                    if (z <= depth[o]) continue;
                    depth[o] = z;
                    double tu = wa * m.uv[a * 2] + wb * m.uv[b * 2] + wc * m.uv[c * 2];
                    double tv = wa * m.uv[a * 2 + 1] + wb * m.uv[b * 2 + 1] + wc * m.uv[c * 2 + 1];
                    int tx = (int) Math.min(tw - 1, Math.max(0, tu * tw));
                    int ty = (int) Math.min(th - 1, Math.max(0, tv * th));
                    int texel = tex[ty * tw + tx];
                    double nx = wa * n[a * 3] + wb * n[b * 3] + wc * n[c * 3];
                    double ny = wa * n[a * 3 + 1] + wb * n[b * 3 + 1] + wc * n[c * 3 + 1];
                    double nz = wa * n[a * 3 + 2] + wb * n[b * 3 + 2] + wc * n[c * 3 + 2];
                    double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (len < 1e-9) len = 1;
                    double lambert = Math.max(0, (nx * light[0] + ny * light[1] + nz * light[2]) / len);
                    double facing = Math.max(0, (nx * d[0] + ny * d[1] + nz * d[2]) / len);
                    double shade = Math.min(1.15, 0.58 + 0.42 * lambert + 0.12 * facing);
                    int rr = (int) Math.min(255, ((texel >> 16) & 0xff) * shade);
                    int gg = (int) Math.min(255, ((texel >> 8) & 0xff) * shade);
                    int bb = (int) Math.min(255, (texel & 0xff) * shade);
                    color[o] = 0xff000000 | (rr << 16) | (gg << 8) | bb;
                }
            }
        }
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int A = 0, R = 0, G = 0, B = 0;
                for (int j = 0; j < ss; j++) {
                    for (int i = 0; i < ss; i++) {
                        int c = color[(y * ss + j) * w + x * ss + i];
                        int al = (c >>> 24);
                        A += al;
                        R += ((c >> 16) & 0xff) * al;
                        G += ((c >> 8) & 0xff) * al;
                        B += (c & 0xff) * al;
                    }
                }
                if (A == 0) continue;
                out[y * size + x] = ((A / (ss * ss)) << 24) | ((R / A) << 16) | ((G / A) << 8) | (B / A);
            }
        }
        return out;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / l, v[1] / l, v[2] / l};
    }

    // ---------------------------------------------------------------- glTF

    /**
     * One static frame laid out like the Blender export that Maps draws: scene root "Armature" scaled 0.01
     * holding the mesh node and a single joint; a one-joint rest-pose skin (harmless whether or not the
     * engine skins), one material, raw positions in metres.
     */
    private static byte[] glb(Model m, double[][] posed, byte[] png) throws IOException {
        double[] p = posed[0], n = posed[1];
        int vc = m.vertexCount();
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        List<String> views = new ArrayList<>();
        List<String> accessors = new ArrayList<>();

        float[] minP = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] maxP = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        ByteBuffer bp = le(vc * 12), bn = le(vc * 12), bt = le(vc * 8), bj = le(vc * 4), bw = le(vc * 16);
        for (int i = 0; i < vc; i++) {
            for (int k = 0; k < 3; k++) {
                float f = (float) p[i * 3 + k];
                bp.putFloat(f);
                minP[k] = Math.min(minP[k], f);
                maxP[k] = Math.max(maxP[k], f);
                bn.putFloat((float) n[i * 3 + k]);
            }
            bt.putFloat(m.uv[i * 2]).putFloat(m.uv[i * 2 + 1]);
            bj.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
            bw.putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f);
        }
        ByteBuffer bi = le(m.indices.length * 2);
        for (int i : m.indices) bi.putShort((short) i);
        ByteBuffer ibm = le(64);
        double[] inv = scaling(1 / GLTF_SCALE, 1 / GLTF_SCALE, 1 / GLTF_SCALE);
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) ibm.putFloat((float) inv[r * 4 + c]); // column-major

        int vp = view(bin, views, bp.array(), 34962);
        int vn = view(bin, views, bn.array(), 34962);
        int vt = view(bin, views, bt.array(), 34962);
        int vjv = view(bin, views, bj.array(), 34962);
        int vwv = view(bin, views, bw.array(), 34962);
        int vi = view(bin, views, bi.array(), 34963);
        int vm = view(bin, views, ibm.array(), -1);
        int vimg = view(bin, views, png, -1);

        accessors.add(String.format(Locale.ROOT,
            "{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC3\",\"min\":[%s,%s,%s],\"max\":[%s,%s,%s]}",
            vp, vc, minP[0], minP[1], minP[2], maxP[0], maxP[1], maxP[2]));
        accessors.add(String.format(Locale.ROOT, "{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC3\"}", vn, vc));
        accessors.add(String.format(Locale.ROOT, "{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC2\"}", vt, vc));
        accessors.add(String.format(Locale.ROOT, "{\"bufferView\":%d,\"componentType\":5121,\"count\":%d,\"type\":\"VEC4\"}", vjv, vc));
        accessors.add(String.format(Locale.ROOT, "{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC4\"}", vwv, vc));
        accessors.add(String.format(Locale.ROOT, "{\"bufferView\":%d,\"componentType\":5123,\"count\":%d,\"type\":\"SCALAR\"}", vi, m.indices.length));
        accessors.add(String.format(Locale.ROOT, "{\"bufferView\":%d,\"componentType\":5126,\"count\":1,\"type\":\"MAT4\"}", vm));

        String json = "{\"asset\":{\"generator\":\"DanielTheManiel Morphe Patches DittoBuilder\",\"version\":\"2.0\"},"
            + "\"scene\":0,\"scenes\":[{\"name\":\"Scene\",\"nodes\":[2]}],"
            + "\"nodes\":[{\"name\":\"Root\"},{\"name\":\"Ditto\",\"mesh\":0,\"skin\":0},"
            + "{\"name\":\"Armature\",\"children\":[0,1],\"scale\":[0.01,0.01,0.01]}],"
            + "\"skins\":[{\"name\":\"Armature\",\"joints\":[0],\"inverseBindMatrices\":6}],"
            + "\"meshes\":[{\"name\":\"Ditto\",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,"
            + "\"TEXCOORD_0\":2,\"JOINTS_0\":3,\"WEIGHTS_0\":4},\"indices\":5,\"material\":0}]}],"
            + "\"materials\":[{\"name\":\"Ditto\",\"doubleSided\":true,\"pbrMetallicRoughness\":{\"baseColorTexture\":"
            + "{\"index\":0},\"metallicFactor\":0,\"roughnessFactor\":0.6}}],"
            + "\"textures\":[{\"sampler\":0,\"source\":0}],\"samplers\":[{\"magFilter\":9728,\"minFilter\":9984}],"
            + "\"images\":[{\"bufferView\":" + vimg + ",\"mimeType\":\"image/png\",\"name\":\"texture\"}],"
            + "\"accessors\":[" + String.join(",", accessors) + "],"
            + "\"bufferViews\":[" + String.join(",", views) + "],"
            + "\"buffers\":[{\"byteLength\":" + bin.size() + "}]}";

        byte[] js = json.getBytes(StandardCharsets.UTF_8);
        int jsLen = (js.length + 3) & ~3, binLen = (bin.size() + 3) & ~3;
        ByteBuffer out = le(12 + 8 + jsLen + 8 + binLen);
        out.putInt(0x46546C67).putInt(2).putInt(12 + 8 + jsLen + 8 + binLen);
        out.putInt(jsLen).putInt(0x4E4F534A).put(js);
        for (int i = js.length; i < jsLen; i++) out.put((byte) ' ');
        out.putInt(binLen).putInt(0x004E4942).put(bin.toByteArray());
        for (int i = bin.size(); i < binLen; i++) out.put((byte) 0);
        return out.array();
    }

    private static int view(ByteArrayOutputStream bin, List<String> views, byte[] data, int target) {
        while (bin.size() % 4 != 0) bin.write(0);
        int offset = bin.size();
        bin.write(data, 0, data.length);
        views.add("{\"buffer\":0,\"byteOffset\":" + offset + ",\"byteLength\":" + data.length
            + (target > 0 ? ",\"target\":" + target : "") + "}");
        return views.size() - 1;
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    // ---------------------------------------------------------------- matrices (row-major 4x4)

    private static final double[] ROT_X_90 = rotX(Math.PI / 2); // bone frame: X left, Y up, Z front

    private static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] translation(double x, double y, double z) {
        return new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1};
    }

    private static double[] scaling(double x, double y, double z) {
        return new double[]{x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1};
    }

    private static double[] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
    }

    private static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
    }

    private static double[] rotZ(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] axisAngle(double x, double y, double z, double a) {
        double len = Math.sqrt(x * x + y * y + z * z);
        x /= len;
        y /= len;
        z /= len;
        double c = Math.cos(a), s = Math.sin(a), t = 1 - c;
        return new double[]{
            t * x * x + c, t * x * y - s * z, t * x * z + s * y, 0,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x, 0,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c, 0,
            0, 0, 0, 1};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j];
                r[i * 4 + j] = s;
            }
        return r;
    }

    /** Inverse of rotation + translation (no scale). */
    private static double[] invertRigid(double[] m) {
        double[] r = new double[16];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i * 4 + j] = m[j * 4 + i];
        for (int i = 0; i < 3; i++) r[i * 4 + 3] = -(r[i * 4] * m[3] + r[i * 4 + 1] * m[7] + r[i * 4 + 2] * m[11]);
        r[15] = 1;
        return r;
    }

    // ---------------------------------------------------------------- XML / IO helpers

    private static String localName(Element e) {
        String n = e.getTagName();
        int c = n.indexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }

    private static List<Element> elements(Element root, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (localName(e).equals(tag)) out.add(e);
        }
        return out;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element && localName((Element) c).equals(tag)) out.add((Element) c);
        }
        return out;
    }

    private static Element first(Element parent, String tag) {
        List<Element> l = children(parent, tag);
        if (!l.isEmpty()) return l.get(0);
        List<Element> deep = elements(parent, tag);
        return deep.isEmpty() ? null : deep.get(0);
    }

    private static double[] doubles(String s) {
        String[] parts = s.trim().split("\\s+");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i]);
        return out;
    }

    private static int[] ints(String s) {
        String t = s.trim();
        if (t.isEmpty()) return new int[0];
        String[] parts = t.split("\\s+");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    private static String valueKey(double[] src, int i, int stride) {
        if (src == null || i < 0) return "-";
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < Math.min(stride, 3); k++) sb.append(Math.round(src[i * stride + k] * 1e5)).append(',');
        return sb.toString();
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void write(File f, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
