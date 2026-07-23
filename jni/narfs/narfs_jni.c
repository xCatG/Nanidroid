#include "narfs_core.h"
#include "narfs_utf.h"

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define JNI_INPUT_ERROR 100
#define JNI_NATIVE_ERROR 101
#define ROOT_BYTES 4096

typedef struct captured_entry {
    char path[NARFS_MAX_PATH_BYTES + 1];
    jint type;
    jlong facts[3];
} captured_entry;

typedef struct capture {
    captured_entry *entries;
    uint32_t count;
    int failed;
} capture;

static int visit_entry(const narfs_entry *entry, int fd, void *context) {
    capture *captured = (capture *) context;
    uint16_t checked[NARFS_MAX_PATH_BYTES + 1];
    size_t bytes = strlen(entry->relative_path), units;
    (void) fd;
    if (captured->count >= NARFS_MAX_ENTRIES || bytes > NARFS_MAX_PATH_BYTES
            || narfs_utf8_to_utf16((const unsigned char *) entry->relative_path,
                    bytes, checked, NARFS_MAX_PATH_BYTES + 1, &units) != NARFS_UTF_OK) {
        captured->failed = 1;
        return 1;
    }
    captured_entry *output = &captured->entries[captured->count++];
    memcpy(output->path, entry->relative_path, bytes + 1);
    output->type = (jint) entry->type;
    output->facts[0] = (jlong) entry->size;
    output->facts[1] = (jlong) entry->device;
    output->facts[2] = (jlong) entry->inode;
    return 0;
}

/* -1 preserves a pending VM exception; 0 is a typed invalid input. */
static int copy_input(
        JNIEnv *env, jstring input, unsigned char *output, size_t capacity) {
    const jchar *chars;
    jsize length;
    size_t written;
    narfs_utf_status status;
    if (input == NULL) return 0;
    length = (*env)->GetStringLength(env, input);
    if ((*env)->ExceptionCheck(env)) return -1;
    chars = (*env)->GetStringChars(env, input, NULL);
    if (chars == NULL || (*env)->ExceptionCheck(env)) return -1;
    status = narfs_utf16_to_utf8(
            (const uint16_t *) chars, (size_t) length,
            output, capacity - 1, &written);
    (*env)->ReleaseStringChars(env, input, chars);
    if ((*env)->ExceptionCheck(env)) return -1;
    if (status != NARFS_UTF_OK) return 0;
    output[written] = '\0';
    return 1;
}

static jobject make_result(
        JNIEnv *env, jint state, jint error, jint cleanup,
        jlong total, const capture *captured) {
    const uint32_t count = captured == NULL ? 0 : captured->count;
    jclass owner = NULL, string_class = NULL;
    jmethodID factory;
    jobjectArray paths = NULL;
    jintArray types = NULL;
    jlongArray facts = NULL;
    jobject result = NULL;
    uint32_t index;
    owner = (*env)->FindClass(
            env, "com/cattailsw/nanidroid/install/NarFilesystemInspector");
    if (owner == NULL || (*env)->ExceptionCheck(env)) goto done;
    factory = (*env)->GetStaticMethodID(env, owner, "fromNative",
            "(IIIIJ[Ljava/lang/String;[I[J)"
            "Lcom/cattailsw/nanidroid/install/NarFilesystemInspector$Result;");
    if (factory == NULL || (*env)->ExceptionCheck(env)) goto done;
    string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL || (*env)->ExceptionCheck(env)) goto done;
    paths = (*env)->NewObjectArray(env, (jsize) count, string_class, NULL);
    types = (*env)->NewIntArray(env, (jsize) count);
    facts = (*env)->NewLongArray(env, (jsize) (count * 3));
    if (paths == NULL || types == NULL || facts == NULL
            || (*env)->ExceptionCheck(env)) goto done;
    for (index = 0; index < count; index++) {
        const captured_entry *entry = &captured->entries[index];
        uint16_t utf16[NARFS_MAX_PATH_BYTES + 1];
        size_t units;
        jstring path;
        if (narfs_utf8_to_utf16((const unsigned char *) entry->path,
                strlen(entry->path), utf16, NARFS_MAX_PATH_BYTES + 1,
                &units) != NARFS_UTF_OK) goto done;
        path = (*env)->NewString(env, (const jchar *) utf16, (jsize) units);
        if (path == NULL || (*env)->ExceptionCheck(env)) goto done;
        (*env)->SetObjectArrayElement(env, paths, (jsize) index, path);
        (*env)->DeleteLocalRef(env, path);
        if ((*env)->ExceptionCheck(env)) goto done;
        (*env)->SetIntArrayRegion(env, types, (jsize) index, 1, &entry->type);
        if ((*env)->ExceptionCheck(env)) goto done;
        (*env)->SetLongArrayRegion(
                env, facts, (jsize) (index * 3), 3, entry->facts);
        if ((*env)->ExceptionCheck(env)) goto done;
    }
    result = (*env)->CallStaticObjectMethod(
            env, owner, factory, state, error, cleanup, (jint) count,
            total, paths, types, facts);
    if ((*env)->ExceptionCheck(env)) result = NULL;
done:
    if (facts != NULL) (*env)->DeleteLocalRef(env, facts);
    if (types != NULL) (*env)->DeleteLocalRef(env, types);
    if (paths != NULL) (*env)->DeleteLocalRef(env, paths);
    if (string_class != NULL) (*env)->DeleteLocalRef(env, string_class);
    if (owner != NULL) (*env)->DeleteLocalRef(env, owner);
    return result;
}

JNIEXPORT jobject JNICALL
Java_com_cattailsw_nanidroid_install_NarFilesystemInspector_nativeInspect(
        JNIEnv *env, jclass owner, jstring root_value, jstring target_value) {
    unsigned char root[ROOT_BYTES + 1], target[NARFS_MAX_COMPONENT_BYTES + 1];
    capture captured = {0};
    narfs_options options;
    narfs_result inspected;
    int root_status, target_status;
    jobject output;
    (void) owner;
    root_status = copy_input(env, root_value, root, sizeof(root));
    if (root_status < 0) return NULL;
    target_status = copy_input(env, target_value, target, sizeof(target));
    if (target_status < 0) return NULL;
    if (!root_status || !target_status) {
        return make_result(env, NARFS_STATE_ERROR, JNI_INPUT_ERROR, NARFS_OK, 0, NULL);
    }
    captured.entries = (captured_entry *) calloc(
            NARFS_MAX_ENTRIES, sizeof(captured_entry));
    if (captured.entries == NULL) {
        return make_result(env, NARFS_STATE_ERROR, JNI_NATIVE_ERROR, NARFS_OK, 0, NULL);
    }
    options = narfs_default_options();
    inspected = narfs_inspect(
            (const char *) root, (const char *) target,
            &options, visit_entry, &captured);
    if (captured.failed) {
        inspected.state = NARFS_STATE_ERROR;
        inspected.error = (narfs_error) JNI_NATIVE_ERROR;
        captured.count = 0;
    } else if (inspected.state != NARFS_STATE_PRESENT) captured.count = 0;
    output = make_result(env, inspected.state, inspected.error,
            inspected.cleanup_error, (jlong) inspected.total_file_size, &captured);
    free(captured.entries);
    return output;
}
