package com.cattailsw.nanidroid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cattailsw.nanidroid.util.NarUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Characterizes only successful, trusted forced-id extraction of a bounded,
 * collision-free archive. Rejection and containment policy belong to D9b.
 */
public final class NarArchiveCharacterizationTest {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final byte[] INSTALL_DESCRIPTOR = ascii(
            "type,ghost\n"
                    + "directory,descriptor-ghost\n");
    private static final byte[] GHOST_DESCRIPTOR = ascii(
            "name,Seed Ghost\n"
                    + "sakura.name,Seed Sakura\n");
    private static final byte[] SHELL_DESCRIPTOR = ascii("name,Master Shell\n");
    private static final byte[] README = ascii("Synthetic forced-id archive.\n");
    private static final byte[] BINARY_PAYLOAD = new byte[] {
            (byte) 0x00,
            (byte) 0x7f,
            (byte) 0x80,
            (byte) 0xff,
    };

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void requiredMigrationInvariant_trustedForcedIdPreservesExactTreeAndBytes()
            throws Exception {
        File sourceRoot = temporaryFolder.newFolder("source");
        File installRoot = temporaryFolder.newFolder("install");
        File archive = new File(sourceRoot, "synthetic.nar");
        writeArchive(archive);

        boolean installed = NarUtil.readNarArchive(
                archive.getAbsolutePath(),
                installRoot.getAbsolutePath(),
                "seed-ghost");

        assertTrue(installed);
        assertArrayEquals(new String[] {"seed-ghost"}, sortedNames(installRoot));
        assertFalse(new File(installRoot, "descriptor-ghost").exists());
        assertEquals(
                Arrays.asList(
                        "seed-ghost/",
                        "seed-ghost/ghost/",
                        "seed-ghost/ghost/master/",
                        "seed-ghost/ghost/master/data/",
                        "seed-ghost/ghost/master/data/payload.bin",
                        "seed-ghost/ghost/master/descript.txt",
                        "seed-ghost/install.txt",
                        "seed-ghost/readme.txt",
                        "seed-ghost/shell/",
                        "seed-ghost/shell/master/",
                        "seed-ghost/shell/master/descript.txt"),
                sortedTree(installRoot));

        File installedRoot = new File(installRoot, "seed-ghost");
        assertArrayEquals(
                INSTALL_DESCRIPTOR,
                readBytes(new File(installedRoot, "install.txt")));
        assertArrayEquals(
                GHOST_DESCRIPTOR,
                readBytes(new File(installedRoot, "ghost/master/descript.txt")));
        assertArrayEquals(
                SHELL_DESCRIPTOR,
                readBytes(new File(installedRoot, "shell/master/descript.txt")));
        assertArrayEquals(
                README,
                readBytes(new File(installedRoot, "readme.txt")));
        byte[] installedPayload = readBytes(
                new File(installedRoot, "ghost/master/data/payload.bin"));
        assertArrayEquals(BINARY_PAYLOAD, installedPayload);
        assertEquals(
                "89273d2f70b93285bb7ddb4bcee86a5347ca7159352e3cbdd20c23e9d1e507d3",
                sha256(installedPayload));
    }

    private static void writeArchive(File archive) throws Exception {
        ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive));
        try {
            // Deliberately avoid descriptor-first ordering.
            writeEntry(output, "ghost/master/data/payload.bin", BINARY_PAYLOAD);
            writeEntry(output, "readme.txt", README);
            writeEntry(output, "ghost/master/descript.txt", GHOST_DESCRIPTOR);
            writeEntry(output, "install.txt", INSTALL_DESCRIPTOR);
            writeEntry(output, "shell/master/descript.txt", SHELL_DESCRIPTOR);
        } finally {
            output.close();
        }
    }

    private static void writeEntry(
            ZipOutputStream output,
            String name,
            byte[] content) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private static String[] sortedNames(File directory) {
        String[] names = directory.list();
        if (names == null) {
            return new String[0];
        }
        Arrays.sort(names);
        return names;
    }

    private static List<String> sortedTree(File root) {
        List<String> paths = new ArrayList<String>();
        collectTree(root, "", paths);
        Collections.sort(paths);
        return paths;
    }

    private static void collectTree(
            File directory,
            String prefix,
            List<String> paths) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });
        for (File child : children) {
            String relative = prefix + child.getName();
            if (child.isDirectory()) {
                paths.add(relative + "/");
                collectTree(child, relative + "/", paths);
            } else {
                paths.add(relative);
            }
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(ASCII);
    }
}
