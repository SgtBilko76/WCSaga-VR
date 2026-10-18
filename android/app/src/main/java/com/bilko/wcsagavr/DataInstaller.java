package com.bilko.wcsagavr;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;

/**
 * Downloads the game data archives (7z, listed in assets/wcs_data.json), checks their
 * sha256 and unpacks them into the mod folder. Plain Java (no Android classes) so it can
 * be tested on a PC.
 *
 * Progress survives interruptions: archives are downloaded to <state>/<name>.part and
 * resumed with HTTP Range requests; <name>.ok marks a verified download and <name>.done
 * an unpacked archive. The state folder is removed when everything is installed.
 */
public final class DataInstaller implements Runnable {
    public static final class Archive {
        public String name, sha256, dest;
        public long size, unpacked;
        public List<String> urls;
    }

    public static final int IDLE = 0, DOWNLOADING = 1, VERIFYING = 2, EXTRACTING = 3, DONE = 4, FAILED = 5;

    // Read by the UI thread.
    public volatile int phase = IDLE;
    public volatile int index, count;
    public volatile String current = "", error;
    public volatile long doneBytes, totalBytes;   // overall: download sizes + unpacked sizes
    public volatile long stepDone, stepTotal;     // current download / check / unpack step
    public volatile long downloaded;              // bytes received from the network (for speed)

    private final List<Archive> archives;
    private final File modDir, stateDir;

    public DataInstaller(List<Archive> archives, File modDir, File stateDir) {
        this.archives = archives;
        this.modDir = modDir;
        this.stateDir = stateDir;
        this.count = archives.size();
        long t = 0;
        for (Archive a : archives) t += a.size + a.unpacked;
        this.totalBytes = t;
    }

    /** Bytes still needed on storage (remaining unpacked data + the largest remaining archive). */
    public static long spaceNeeded(List<Archive> archives, File stateDir) {
        long need = 0, biggest = 0;
        for (Archive a : archives) {
            if (new File(stateDir, a.name + ".done").exists()) continue;
            need += a.unpacked;
            long left = a.size - Math.max(new File(stateDir, a.name).length(), new File(stateDir, a.name + ".part").length());
            biggest = Math.max(biggest, Math.max(left, 0));
        }
        return need + biggest;
    }

    public boolean isRunning() {
        int p = phase;
        return p == DOWNLOADING || p == VERIFYING || p == EXTRACTING;
    }

    @Override
    public void run() {
        phase = DOWNLOADING;
        try {
            if (!stateDir.isDirectory() && !stateDir.mkdirs()) throw new IOException("cannot create " + stateDir);
            if (!modDir.isDirectory() && !modDir.mkdirs()) throw new IOException("cannot create " + modDir);
            for (int i = 0; i < archives.size(); i++) {
                Archive a = archives.get(i);
                index = i + 1;
                current = a.name;
                File done = new File(stateDir, a.name + ".done");
                if (done.exists()) {
                    doneBytes += a.size + a.unpacked;
                    continue;
                }
                File arc = new File(stateDir, a.name);
                File ok = new File(stateDir, a.name + ".ok");
                if (ok.exists() && arc.length() == a.size) {
                    doneBytes += a.size;
                } else {
                    download(a, arc);
                    ok.createNewFile();
                }
                extract(a, arc);
                if (!done.createNewFile() && !done.exists()) throw new IOException("cannot write " + done);
                arc.delete();
                ok.delete();
            }
            deleteTree(stateDir);
            phase = DONE;
        } catch (Throwable t) {
            error = t.getMessage() != null ? t.getMessage() : t.toString();
            phase = FAILED;
        }
    }

    private void download(Archive a, File arc) throws IOException {
        File part = new File(stateDir, a.name + ".part");
        final long base = doneBytes;
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            String url = a.urls.get(attempt % a.urls.size());
            try {
                fetch(url, part, a.size, base);
                phase = VERIFYING;
                String sum = sha256(part);
                if (sum.equalsIgnoreCase(a.sha256)) {
                    arc.delete();
                    if (!part.renameTo(arc)) throw new IOException("cannot rename " + part);
                    doneBytes = base + a.size;
                    return;
                }
                part.delete();
                doneBytes = base;
                last = new IOException("checksum mismatch for " + a.name);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(2000L * Math.min(attempt + 1, 5));
                } catch (InterruptedException ie) {
                    throw new IOException("interrupted");
                }
            }
        }
        throw new IOException("Download of " + a.name + " failed: " + (last != null ? last.getMessage() : "?"));
    }

    private void fetch(String url, File part, long size, long base) throws IOException {
        long have = part.exists() ? part.length() : 0;
        if (have > size) {
            part.delete();
            have = 0;
        }
        phase = DOWNLOADING;
        stepTotal = size;
        stepDone = have;
        doneBytes = base + have;
        if (have == size) return;

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "WCSaga-VR installer");
        if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = c.getResponseCode();
            boolean append;
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && !cr.startsWith("bytes " + have + "-")) throw new IOException("bad Content-Range " + cr);
                append = true;
            } else if (code == 200) {
                append = false;
                have = 0;
            } else {
                throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
            }
            stepDone = have;
            doneBytes = base + have;
            byte[] buf = new byte[1 << 16];
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    have += n;
                    stepDone = have;
                    doneBytes = base + have;
                    downloaded += n;
                    if (have > size) throw new IOException("server sent too much data");
                }
            }
        } finally {
            c.disconnect();
        }
        if (have != size) throw new IOException("connection closed early");
    }

    private String sha256(File f) throws IOException {
        stepTotal = f.length();
        stepDone = 0;
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                stepDone += n;
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e.toString());
        }
    }

    private void extract(Archive a, File arc) throws IOException {
        phase = EXTRACTING;
        stepTotal = a.unpacked;
        stepDone = 0;
        final long base = doneBytes;
        File root = new File(modDir, a.dest == null ? "" : a.dest).getCanonicalFile();
        String rootPrefix = root.getPath() + File.separator;
        byte[] buf = new byte[1 << 20];
        try (SevenZFile z = new SevenZFile(arc)) {
            SevenZArchiveEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                File f = new File(root, name).getCanonicalFile();
                if (!f.getPath().startsWith(rootPrefix)) throw new IOException("bad path in archive: " + name);
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                File dir = f.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                try (OutputStream out = new FileOutputStream(f)) {
                    if (e.hasStream()) {
                        int n;
                        while ((n = z.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            stepDone += n;
                            doneBytes = base + stepDone;
                        }
                    }
                }
            }
        }
        doneBytes = base + a.unpacked;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
