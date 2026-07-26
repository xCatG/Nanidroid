import copy
import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import inspect_narfs_stage as stage


ROOT = Path(__file__).resolve().parents[1]
BOUNDARY_HASHES = {
    "jni/narfs/narfs_stage.c":
        "825504b5426fcfc305d85be501edab1ffd919487dfc74fa1984d3adc7676aef7",
    "jni/narfs/narfs_stage.h":
        "6ac7873e476b936bf1e6ec6ad7337316dcd5a37c2076ae40a04a81f075da910d",
    "jni/narfs/narfs_core.c":
        "6160699d0a2a3fdc2ffdddc3e7b225110a3749258e51159a75c2db1ae931692d",
    "jni/narfs/narfs_core.h":
        "c929545356a666849e9f0a92e5490dc33f1629718383b3b68983f79e0c23d756",
    "jni/narfs/narfs_sha256.c":
        "e23b994bba8ae1c1c45a8e1af051d6f144d237465b7e91418cc1bcb13e34857d",
    "jni/narfs/narfs_sha256.h":
        "34e0968b8315dffbafa0537fc81e6ae6228db70fd8c0d2038ea66394c1fcf17b",
}
SYMBOLS = """\
File: archive(narfs_stage.o)
Num: Value Size Type Bind Vis Ndx Name
 1: 0 10 FUNC GLOBAL DEFAULT 1 narfs_default_stage_options
 2: 0 10 FUNC GLOBAL DEFAULT 1 narfs_stage_clone_retained
 3: 0 10 FUNC GLOBAL DEFAULT 1 narfs_stage_discard
 4: 0 10 FUNC GLOBAL DEFAULT 1 narfs_stage_existing
 5: 0 10 FUNC GLOBAL DEFAULT 1 narfs_stage_result_dispose
""" + "".join(
    f" {index}: 0 0 NOTYPE GLOBAL DEFAULT UND {value}\n"
    for index, value in enumerate(
        stage.IMPORTS + [
            "__aeabi_unwind_cpp_pr0", "__aeabi_unwind_cpp_pr1",
            "__stack_chk_fail", "__stack_chk_guard",
        ], 6))


def ndk_commands(build):
    compiler = (
        "/opt/android-ndk-r14b/toolchains/arm-linux-androideabi-4.9/"
        "prebuilt/linux-x86_64/bin/arm-linux-androideabi-gcc")
    sysroot = "/opt/android-ndk-r14b/platforms/android-9/arch-arm"
    lane = build / "ndk-armeabi/obj/local/armeabi"
    probe = lane / "objs/narfs_stage_link_probe/narfs_stage_link_probe.o"
    source_object = lane / "objs/narfs_stage/__/narfs_stage.o"
    def compile_command(source, output):
        return (
            f"{compiler} -MMD -MP -MF {output}.d -fpic "
            "-ffunction-sections -funwind-tables -fstack-protector-strong "
            "-no-canonical-prefixes -g -march=armv5te -mtune=xscale "
            "-msoft-float -mthumb -Os -DNDEBUG "
            f"-I{build}/jni/narfs/stage/.. -I{build}/jni/narfs/stage "
            "-DANDROID -std=c99 -Wall -Wextra -Werror -Wa,--noexecstack "
            f"-Wformat -Werror=format-security --sysroot {sysroot} "
            f"-c {source} -o {output}")
    archives = [
        lane / "libnarfs_stage.a", lane / "libnarfs_core.a",
        lane / "libnarfs_sha256.a",
    ]
    return [
        compile_command(
            build / "jni/narfs/narfs_stage.c", source_object),
        compile_command(
            build / "test/native/narfs_stage_link_probe.c", probe),
        f"{compiler} -Wl,--gc-sections -Wl,-z,nocopyreloc "
        f"--sysroot={sysroot} -Wl,-rpath-link={sysroot}/usr/lib "
        f"-Wl,-rpath-link={lane} {probe} "
        + " ".join(str(value) for value in archives)
        + " -lgcc -no-canonical-prefixes -Wl,--no-undefined "
        "-Wl,--build-id -Wl,--no-undefined "
        "-Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now "
        "-Wl,--warn-shared-textrel -Wl,--fatal-warnings -lc -lm "
        f"-o {lane}/narfs_stage_link_probe",
    ]


def fake_readelf(arguments, **_kwargs):
    artifact = arguments[-1]
    if "--file-header" in arguments:
        arm64 = "arm64" in artifact
        return subprocess.CompletedProcess(arguments, 0, f"""\
 Class: {'ELF64' if arm64 else 'ELF32'}
 Type: {'REL' if artifact.endswith('.a') else 'DYN' if arm64 else 'EXEC'}
 Machine: {'AArch64' if arm64 else 'ARM'}
""", "")
    if "--symbols" in arguments:
        value = SYMBOLS.replace("narfs_stage.o", "narfs_stage.c.o") \
            if "cmake" in artifact else SYMBOLS
        return subprocess.CompletedProcess(arguments, 0, value, "")
    return subprocess.CompletedProcess(arguments, 0, "", "")


class NarfsStageStaticContractTest(unittest.TestCase):
    def test_runtime_boundary_is_pinned(self):
        for relative, wanted in BOUNDARY_HASHES.items():
            actual = hashlib.sha256(
                (ROOT / relative).read_bytes().replace(b"\r\n", b"\n")
            ).hexdigest()
            self.assertEqual(wanted, actual, relative)

    def test_declarations_are_guarded_non_discoverable_and_equivalent(self):
        declarations = stage.inspect_declarations(ROOT)
        self.assertEqual(declarations["ndkBuild"], declarations["cmake"])
        self.assertEqual("narfs_stage", declarations["ndkBuild"]["module"])

    @mock.patch("inspect_narfs_stage.subprocess.run", side_effect=fake_readelf)
    def test_archive_probe_and_all_globals_are_exact(self, run):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "libnarfs_stage.a"
            probe = root / "narfs_stage_link_probe"
            archive.write_bytes(b"!<arch>\n")
            probe.write_bytes(b"\x7fELF")
            report = stage.inspect_artifacts(
                archive, probe, Path("readelf"), "armeabi",
                "android-9", "ndk-build")
            self.assertEqual(stage.EXPORTS, report["exports"])
            for mutation in (
                "\nFile: archive(extra.o)\n",
                "\n 20: 0 10 FUNC GLOBAL DEFAULT 1 extra_global\n",
                "\n 20: 0 0 NOTYPE GLOBAL DEFAULT UND unexpected_import\n",
                "\n 20: 0 0 NOTYPE GLOBAL DEFAULT UND __aeabi_llsr\n",
            ):
                run.side_effect = lambda arguments, mutation=mutation, **kwargs: (
                    subprocess.CompletedProcess(
                        arguments, 0, SYMBOLS + mutation, "")
                    if "--symbols" in arguments else fake_readelf(
                        arguments, **kwargs))
                with self.assertRaises(stage.StageContractError):
                    stage.inspect_artifacts(
                        archive, probe, Path("readelf"), "armeabi",
                        "android-9", "ndk-build")
            run.side_effect = lambda arguments, **kwargs: (
                subprocess.CompletedProcess(
                    arguments, 0, " Class: ELF64\n Type: DYN\n"
                    " Machine: AArch64\n", "")
                if "--file-header" in arguments else fake_readelf(
                    arguments, **kwargs))
            with self.assertRaises(stage.StageContractError):
                stage.inspect_artifacts(
                    archive, probe, Path("readelf"), "armeabi",
                    "android-9", "ndk-build")

    def test_build_evidence_rejects_every_provenance_class(self):
        with tempfile.TemporaryDirectory() as directory:
            build = Path(directory) / "build"
            evidence = build / "ndk-armeabi/build.log"
            evidence.parent.mkdir(parents=True)
            commands = ndk_commands(build)
            evidence.write_text("\n".join(commands), encoding="utf-8")
            report = stage.inspect_build_evidence(
                evidence, "ndk-build", "armeabi", "android-9")
            self.assertEqual(["source", "probe"], report["compileOrder"])
            compiler = commands[0].split()[0]
            lane = build / "ndk-armeabi/obj/local/armeabi"
            probe = lane / "objs/narfs_stage_link_probe/narfs_stage_link_probe.o"
            archive = lane / "libnarfs_stage.a"
            core = lane / "libnarfs_core.a"
            sha256 = lane / "libnarfs_sha256.a"
            mutations = (
                [commands[0].replace("narfs_stage.c", "other.c"),
                 *commands[1:]],
                [commands[0] + " -I/foreign", *commands[1:]],
                [commands[0] + " -Wno-error", *commands[1:]],
                [commands[0] + " -O2", *commands[1:]],
                [commands[0].replace("android-9", "android-21"),
                 *commands[1:]],
                [commands[0].replace(compiler, "/foreign/gcc"),
                 *commands[1:]],
                [*commands[:2], commands[2].replace(
                    f"{probe} {archive}", f"{archive} {probe}")],
                [*commands[:2], commands[2].replace(
                    "libnarfs_core.a", "extra.o libnarfs_core.a")],
                [*commands[:2], commands[2].replace(
                    f"{core} {sha256}", f"{sha256} {core}")],
                [*commands[:2], commands[2].replace(
                    compiler, "/foreign/g++")],
                [*commands[:2],
                 commands[2] + " -O2 -no-canonical-prefixes"],
            )
            for index, changed in enumerate(mutations):
                with self.subTest(mutation=index):
                    evidence.write_text("\n".join(changed), encoding="utf-8")
                    with self.assertRaises(stage.StageContractError):
                        stage.inspect_build_evidence(
                            evidence, "ndk-build", "armeabi", "android-9")

    def test_parity_rejects_contract_drift(self):
        contract = {"contract": {"exports": ["a"]}, "provenance": {"lane": "x"}}
        self.assertEqual("equivalent", stage.compare_contracts(
            contract, copy.deepcopy(contract))["status"])
        changed = copy.deepcopy(contract)
        changed["contract"]["exports"].append("b")
        with self.assertRaises(stage.StageContractError):
            stage.compare_contracts(contract, changed)

    def test_off_lane_is_a_real_build_gate(self):
        script = (ROOT / "docker/narfs-jni/build.sh").read_text()
        self.assertIn(
            "candidate-off graph unexpectedly contains stage", script)
        self.assertIn("NANIDROID_NARFS_STAGE_CANDIDATE", script)
        self.assertIn("APP_MODULES=narfs_stage_link_probe", script)


if __name__ == "__main__":
    unittest.main()
