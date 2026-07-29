#include "narfs_stage.h"
#include "narfs_stage_token.h"
#include "narfs_utf.h"

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define JNI_INPUT_ERROR 100
#define JNI_NATIVE_ERROR 101
#define ROOT_BYTES 4096
#define BEGIN_FACTORY_DESCRIPTOR "(IIIJJ[B[Ljava/lang/String;[I[J[I[B)Lcom/cattailsw/nanidroid/install/NarStagedTree$BeginResult;"

static jint stage_state_code(narfs_state state) {
    return state == NARFS_STATE_ABSENT ? 1
            : state == NARFS_STATE_PRESENT ? 2 : 0;
}

static jint stage_error_code(int error) {
    return (error >= NARFS_OK && error <= NARFS_ERR_CLOSE)
            || error == JNI_INPUT_ERROR || error == JNI_NATIVE_ERROR
            ? (jint) error : JNI_NATIVE_ERROR;
}

/* -1 preserves a VM exception; 0 is invalid input; 1 is success. */
static int stage_copy_input(
        JNIEnv *env, jstring input, unsigned char *output, size_t capacity) {
    const jchar *chars;
    jsize length;
    size_t written;
    narfs_utf_status status;
    if (input == NULL || capacity == 0) return 0;
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

static jobject stage_make_begin(
        JNIEnv *env, const narfs_stage_result *staged) {
    const uint32_t count = staged->entry_count;
    unsigned char wire[NARFS_STAGE_WIRE_BYTES];
    jclass owner = NULL, string_class = NULL;
    jmethodID factory;
    jbyteArray token = NULL, digests = NULL;
    jobjectArray paths = NULL;
    jintArray types = NULL, ordinals = NULL;
    jlongArray sizes = NULL;
    jobject output = NULL;
    uint32_t index;

    if (count > NARFS_MAX_ENTRIES
            || (count > 0 && staged->entries == NULL)) return NULL;
    owner = (*env)->FindClass(
            env, "com/cattailsw/nanidroid/install/NarStagedTree");
    if (owner == NULL || (*env)->ExceptionCheck(env)) goto done;
    factory = (*env)->GetStaticMethodID(
            env, owner, "fromNativeBegin", BEGIN_FACTORY_DESCRIPTOR);
    if (factory == NULL || (*env)->ExceptionCheck(env)) goto done;
    string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL || (*env)->ExceptionCheck(env)) goto done;
    paths = (*env)->NewObjectArray(env, (jsize) count, string_class, NULL);
    if (paths == NULL || (*env)->ExceptionCheck(env)) goto done;
    types = (*env)->NewIntArray(env, (jsize) count);
    if (types == NULL || (*env)->ExceptionCheck(env)) goto done;
    sizes = (*env)->NewLongArray(env, (jsize) count);
    if (sizes == NULL || (*env)->ExceptionCheck(env)) goto done;
    ordinals = (*env)->NewIntArray(env, (jsize) count);
    if (ordinals == NULL || (*env)->ExceptionCheck(env)) goto done;
    digests = (*env)->NewByteArray(env, (jsize) (count * 32U));
    if (digests == NULL || (*env)->ExceptionCheck(env)) goto done;
    if (staged->token.session_name[0] != '\0') {
        if (!narfs_stage_token_encode(
                &staged->token, wire, sizeof(wire))) goto done;
        token = (*env)->NewByteArray(env, (jsize) sizeof(wire));
        if (token == NULL || (*env)->ExceptionCheck(env)) goto done;
        (*env)->SetByteArrayRegion(
                env, token, 0, (jsize) sizeof(wire), (const jbyte *) wire);
        if ((*env)->ExceptionCheck(env)) goto done;
    }
    for (index = 0; index < count; index++) {
        const narfs_stage_entry *entry = &staged->entries[index];
        const char *relative_path = entry->relative_path;
        uint16_t utf16[NARFS_MAX_PATH_BYTES + 1];
        size_t bytes;
        size_t units;
        jstring path;
        jint type = (jint) entry->type;
        jlong size;
        jint ordinal = entry->type == NARFS_ENTRY_FILE
                ? (jint) entry->blob_ordinal : -1;
        if (relative_path == NULL || entry->size > (uint64_t) INT64_MAX)
            goto done;
        bytes = strlen(relative_path);
        size = (jlong) entry->size;
        if (bytes > NARFS_MAX_PATH_BYTES
                || narfs_utf8_to_utf16(
                (const unsigned char *) relative_path, bytes,
                utf16, NARFS_MAX_PATH_BYTES + 1,
                &units) != NARFS_UTF_OK) goto done;
        path = (*env)->NewString(
                env, (const jchar *) utf16, (jsize) units);
        if (path == NULL || (*env)->ExceptionCheck(env)) goto done;
        (*env)->SetObjectArrayElement(env, paths, (jsize) index, path);
        (*env)->DeleteLocalRef(env, path);
        if ((*env)->ExceptionCheck(env)) goto done;
        (*env)->SetIntArrayRegion(env, types, (jsize) index, 1, &type);
        if ((*env)->ExceptionCheck(env)) goto done;
        (*env)->SetLongArrayRegion(env, sizes, (jsize) index, 1, &size);
        if ((*env)->ExceptionCheck(env)) goto done;
        (*env)->SetIntArrayRegion(
                env, ordinals, (jsize) index, 1, &ordinal);
        if ((*env)->ExceptionCheck(env)) goto done;
        (*env)->SetByteArrayRegion(
                env, digests, (jsize) (index * 32U), 32,
                (const jbyte *) entry->sha256);
        if ((*env)->ExceptionCheck(env)) goto done;
    }
    output = (*env)->CallStaticObjectMethod(
            env, owner, factory,
            stage_state_code(staged->inspected.state),
            stage_error_code(staged->inspected.error),
            stage_error_code(staged->inspected.cleanup_error),
            (jlong) staged->inspected.storage_device,
            (jlong) staged->inspected.storage_inode,
            token, paths, types, sizes, ordinals, digests);
    if ((*env)->ExceptionCheck(env)) {
        output = NULL;
        goto done;
    }
done:
    if (digests != NULL) (*env)->DeleteLocalRef(env, digests);
    if (ordinals != NULL) (*env)->DeleteLocalRef(env, ordinals);
    if (sizes != NULL) (*env)->DeleteLocalRef(env, sizes);
    if (types != NULL) (*env)->DeleteLocalRef(env, types);
    if (paths != NULL) (*env)->DeleteLocalRef(env, paths);
    if (token != NULL) (*env)->DeleteLocalRef(env, token);
    if (string_class != NULL) (*env)->DeleteLocalRef(env, string_class);
    if (owner != NULL) (*env)->DeleteLocalRef(env, owner);
    return output;
}

JNIEXPORT jobject JNICALL
Java_com_cattailsw_nanidroid_install_NarStagedTree_nativeBegin(
        JNIEnv *env, jclass owner, jstring app_private_root_value,
        jstring trusted_root_value, jstring target_value) {
    unsigned char app_private_root[ROOT_BYTES + 1];
    unsigned char trusted_root[ROOT_BYTES + 1];
    unsigned char target[NARFS_MAX_COMPONENT_BYTES + 1];
    narfs_stage_result staged;
    narfs_stage_options options;
    jobject output;
    int app_status, trusted_status, target_status;
    (void) owner;
    memset(&staged, 0, sizeof(staged));
    app_status = stage_copy_input(env, app_private_root_value,
            app_private_root, sizeof(app_private_root));
    if (app_status < 0) return NULL;
    trusted_status = stage_copy_input(env, trusted_root_value,
            trusted_root, sizeof(trusted_root));
    if (trusted_status < 0) return NULL;
    target_status = stage_copy_input(
            env, target_value, target, sizeof(target));
    if (target_status < 0) return NULL;
    if (!app_status || !trusted_status || !target_status) {
        staged.inspected.state = NARFS_STATE_ERROR;
        staged.inspected.error = (narfs_error) JNI_INPUT_ERROR;
        staged.inspected.cleanup_error = NARFS_OK;
        return stage_make_begin(env, &staged);
    }
    options = narfs_default_stage_options();
    staged = narfs_stage_existing(
            (const char *) trusted_root, (const char *) target,
            (const char *) app_private_root, &options);
    output = stage_make_begin(env, &staged);
    if (output == NULL || (*env)->ExceptionCheck(env)) {
        if (staged.token.session_name[0] != '\0') {
            (void) narfs_stage_discard(
                    (const char *) app_private_root, &staged.token, &options);
        }
        output = NULL;
    }
    narfs_stage_result_dispose(&staged);
    return output;
}

JNIEXPORT jint JNICALL
Java_com_cattailsw_nanidroid_install_NarStagedTree_nativeDiscard(
        JNIEnv *env, jclass owner, jstring app_private_root_value,
        jbyteArray token_value) {
    unsigned char app_private_root[ROOT_BYTES + 1];
    unsigned char wire[NARFS_STAGE_WIRE_BYTES];
    narfs_stage_token token;
    narfs_stage_options options;
    int root_status;
    (void) owner;
    root_status = stage_copy_input(env, app_private_root_value,
            app_private_root, sizeof(app_private_root));
    if (root_status < 0) return JNI_NATIVE_ERROR;
    if (!root_status || token_value == NULL
            || (*env)->GetArrayLength(env, token_value)
            != (jsize) sizeof(wire)
            || (*env)->ExceptionCheck(env)) return JNI_INPUT_ERROR;
    (*env)->GetByteArrayRegion(
            env, token_value, 0, (jsize) sizeof(wire), (jbyte *) wire);
    if ((*env)->ExceptionCheck(env)
            || !narfs_stage_token_decode(
            wire, sizeof(wire), &token)) return JNI_INPUT_ERROR;
    options = narfs_default_stage_options();
    return (jint) narfs_stage_discard(
            (const char *) app_private_root, &token, &options);
}
