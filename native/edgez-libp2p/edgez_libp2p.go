package main

/*
#cgo android LDFLAGS: -llog
#include <jni.h>
#include <stdlib.h>
#include <string.h>

static JavaVM* edgez_vm = NULL;

static void edgez_set_vm_from_env(JNIEnv* env) {
	if (edgez_vm != NULL || env == NULL) {
		return;
	}
	JavaVM* vm = NULL;
	if ((*env)->GetJavaVM(env, &vm) == JNI_OK) {
		edgez_vm = vm;
	}
}

static char* edgez_jstring_to_c(JNIEnv* env, jstring input) {
	if (input == NULL) {
		return NULL;
	}
	const char* raw = (*env)->GetStringUTFChars(env, input, 0);
	if (raw == NULL) {
		return NULL;
	}
	char* out = strdup(raw);
	(*env)->ReleaseStringUTFChars(env, input, raw);
	return out;
}

static jstring edgez_c_to_jstring(JNIEnv* env, const char* input) {
	if (input == NULL) {
		input = "";
	}
	return (*env)->NewStringUTF(env, input);
}

static char* edgez_jbytearray_to_c(JNIEnv* env, jbyteArray input, int* length) {
	if (length != NULL) {
		*length = 0;
	}
	if (input == NULL) {
		return NULL;
	}
	jsize size = (*env)->GetArrayLength(env, input);
	if (length != NULL) {
		*length = (int)size;
	}
	if (size <= 0) {
		return NULL;
	}
	char* out = (char*)malloc((size_t)size);
	if (out == NULL) {
		return NULL;
	}
	(*env)->GetByteArrayRegion(env, input, 0, size, (jbyte*)out);
	return out;
}

static jobject edgez_new_global_ref(JNIEnv* env, jobject obj) {
	if (obj == NULL) {
		return NULL;
	}
	return (*env)->NewGlobalRef(env, obj);
}

static void edgez_delete_global_ref(jobject obj) {
	if (edgez_vm == NULL || obj == NULL) {
		return;
	}
	JNIEnv* env = NULL;
	if ((*edgez_vm)->GetEnv(edgez_vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK && env != NULL) {
		(*env)->DeleteGlobalRef(env, obj);
	}
}

static jmethodID edgez_callback_method(JNIEnv* env, jobject callback) {
	if (callback == NULL) {
		return NULL;
	}
	jclass cls = (*env)->GetObjectClass(env, callback);
	if (cls == NULL) {
		return NULL;
	}
	return (*env)->GetMethodID(env, cls, "onMessage", "([B)V");
}

static void edgez_invoke_callback(jobject callback, jmethodID method, char* data, int length) {
	if (edgez_vm == NULL || callback == NULL || method == NULL || data == NULL || length < 0) {
		return;
	}
	JNIEnv* env = NULL;
	int attached = 0;
	if ((*edgez_vm)->GetEnv(edgez_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
		if ((*edgez_vm)->AttachCurrentThread(edgez_vm, &env, NULL) != JNI_OK) {
			return;
		}
		attached = 1;
	}
	jbyteArray payload = (*env)->NewByteArray(env, length);
	if (payload != NULL) {
		(*env)->SetByteArrayRegion(env, payload, 0, length, (const jbyte*)data);
		(*env)->CallVoidMethod(env, callback, method, payload);
		(*env)->DeleteLocalRef(env, payload);
	}
	if (attached) {
		(*edgez_vm)->DetachCurrentThread(edgez_vm);
	}
}
*/
import "C"

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"
	"unsafe"

	libp2p "github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p-pubsub"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/multiformats/go-multiaddr"
)

type meshConfig struct {
	MeshID         string   `json:"mesh_id"`
	Passphrase     string   `json:"passphrase"`
	PrivateKey     string   `json:"private_key"`
	Topic          string   `json:"topic"`
	Listen         string   `json:"listen"`
	BootstrapPeers []string `json:"bootstrap_peers"`
}

type meshState struct {
	ctx    context.Context
	cancel context.CancelFunc
	host   host.Host
	dht    *dht.IpfsDHT
	pubsub *pubsub.PubSub
	topic  *pubsub.Topic
	sub    *pubsub.Subscription
	peerID peer.ID
}

var (
	stateMu      sync.Mutex
	state        *meshState
	callbackMu   sync.RWMutex
	callbackObj  C.jobject
	callbackFunc C.jmethodID
)

func main() {}

//export Java_ai_edgez_edgez_Libp2pNative_start
func Java_ai_edgez_edgez_Libp2pNative_start(env *C.JNIEnv, thiz C.jobject, config C.jstring) C.jstring {
	_ = thiz
	C.edgez_set_vm_from_env(env)
	raw := C.edgez_jstring_to_c(env, config)
	if raw == nil {
		cResult := C.CString(errorJSON("missing config"))
		defer C.free(unsafe.Pointer(cResult))
		return C.edgez_c_to_jstring(env, cResult)
	}
	defer C.free(unsafe.Pointer(raw))
	result := startMesh(C.GoString(raw))
	cResult := C.CString(result)
	defer C.free(unsafe.Pointer(cResult))
	return C.edgez_c_to_jstring(env, cResult)
}

//export Java_ai_edgez_edgez_Libp2pNative_stop
func Java_ai_edgez_edgez_Libp2pNative_stop(env *C.JNIEnv, thiz C.jobject) C.jstring {
	_ = thiz
	C.edgez_set_vm_from_env(env)
	result := stopMesh()
	cResult := C.CString(result)
	defer C.free(unsafe.Pointer(cResult))
	return C.edgez_c_to_jstring(env, cResult)
}

//export Java_ai_edgez_edgez_Libp2pNative_publish
func Java_ai_edgez_edgez_Libp2pNative_publish(env *C.JNIEnv, thiz C.jobject, payload C.jbyteArray) C.jstring {
	_ = thiz
	C.edgez_set_vm_from_env(env)
	var length C.int
	raw := C.edgez_jbytearray_to_c(env, payload, &length)
	var data []byte
	if raw != nil && length > 0 {
		defer C.free(unsafe.Pointer(raw))
		data = C.GoBytes(unsafe.Pointer(raw), length)
	}
	result := publishMesh(data)
	cResult := C.CString(result)
	defer C.free(unsafe.Pointer(cResult))
	return C.edgez_c_to_jstring(env, cResult)
}

//export Java_ai_edgez_edgez_Libp2pNative_setCallback
func Java_ai_edgez_edgez_Libp2pNative_setCallback(env *C.JNIEnv, thiz C.jobject, callback C.jobject) {
	_ = thiz
	C.edgez_set_vm_from_env(env)
	callbackMu.Lock()
	defer callbackMu.Unlock()
	if unsafe.Pointer(callbackObj) != nil {
		C.edgez_delete_global_ref(callbackObj)
		callbackObj = C.jobject(unsafe.Pointer(nil))
		callbackFunc = C.jmethodID(unsafe.Pointer(nil))
	}
	if unsafe.Pointer(callback) != nil {
		callbackObj = C.edgez_new_global_ref(env, callback)
		callbackFunc = C.edgez_callback_method(env, callback)
	}
}

func startMesh(configJSON string) string {
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return errorJSON(err.Error())
	}
	priv, err := privateKeyFromConfig(cfg)
	if err != nil {
		return errorJSON(err.Error())
	}

	stateMu.Lock()
	defer stateMu.Unlock()
	if state != nil {
		stopMeshLocked()
	}

	ctx, cancel := context.WithCancel(context.Background())
	listen := strings.TrimSpace(cfg.Listen)
	if listen == "" {
		listen = "/ip4/0.0.0.0/tcp/0"
	}
	h, err := libp2p.New(
		libp2p.Identity(priv),
		libp2p.ListenAddrStrings(listen),
		libp2p.EnableRelay(),
		libp2p.EnableHolePunching(),
		libp2p.EnableAutoNATv2(),
		libp2p.NATPortMap(),
	)
	if err != nil {
		cancel()
		return errorJSON(fmt.Sprintf("start libp2p host: %v", err))
	}

	kad, err := dht.New(ctx, h, dht.Mode(dht.ModeAuto))
	if err != nil {
		_ = h.Close()
		cancel()
		return errorJSON(fmt.Sprintf("start dht: %v", err))
	}
	if err := kad.Bootstrap(ctx); err != nil {
		_ = kad.Close()
		_ = h.Close()
		cancel()
		return errorJSON(fmt.Sprintf("bootstrap dht: %v", err))
	}
	connectBootstrapPeers(ctx, h, cfg.BootstrapPeers)

	ps, err := pubsub.NewGossipSub(ctx, h)
	if err != nil {
		_ = kad.Close()
		_ = h.Close()
		cancel()
		return errorJSON(fmt.Sprintf("start gossipsub: %v", err))
	}
	topicName := strings.TrimSpace(cfg.Topic)
	if topicName == "" {
		topicName = "/edgez/mesh/" + cfg.MeshID + "/gossip/1.0.0"
	}
	topic, err := ps.Join(topicName)
	if err != nil {
		_ = kad.Close()
		_ = h.Close()
		cancel()
		return errorJSON(fmt.Sprintf("join topic: %v", err))
	}
	sub, err := topic.Subscribe()
	if err != nil {
		_ = topic.Close()
		_ = kad.Close()
		_ = h.Close()
		cancel()
		return errorJSON(fmt.Sprintf("subscribe topic: %v", err))
	}

	state = &meshState{ctx: ctx, cancel: cancel, host: h, dht: kad, pubsub: ps, topic: topic, sub: sub, peerID: h.ID()}
	go readLoop(state)
	return okJSON(map[string]any{
		"state":        "running",
		"peer_id":      h.ID().String(),
		"topic":        topicName,
		"listen_addrs": listenAddrs(h),
	})
}

func stopMesh() string {
	stateMu.Lock()
	defer stateMu.Unlock()
	stopMeshLocked()
	return okJSON(map[string]any{"state": "stopped"})
}

func stopMeshLocked() {
	if state == nil {
		return
	}
	state.cancel()
	if state.sub != nil {
		state.sub.Cancel()
	}
	if state.topic != nil {
		_ = state.topic.Close()
	}
	if state.dht != nil {
		_ = state.dht.Close()
	}
	if state.host != nil {
		_ = state.host.Close()
	}
	state = nil
}

func publishMesh(payload []byte) string {
	stateMu.Lock()
	local := state
	stateMu.Unlock()
	if local == nil || local.topic == nil {
		return errorJSON("libp2p mesh is not running")
	}
	if err := local.topic.Publish(local.ctx, payload); err != nil {
		return errorJSON(fmt.Sprintf("publish: %v", err))
	}
	return okJSON(map[string]any{"state": "published", "bytes": len(payload)})
}

func readLoop(local *meshState) {
	for {
		msg, err := local.sub.Next(local.ctx)
		if err != nil {
			return
		}
		if msg.ReceivedFrom == local.peerID {
			continue
		}
		invokeCallback(msg.Data)
	}
}

func invokeCallback(payload []byte) {
	callbackMu.RLock()
	cb := callbackObj
	method := callbackFunc
	callbackMu.RUnlock()
	if unsafe.Pointer(cb) == nil || unsafe.Pointer(method) == nil || len(payload) == 0 {
		return
	}
	data := C.CBytes(payload)
	defer C.free(data)
	C.edgez_invoke_callback(cb, method, (*C.char)(data), C.int(len(payload)))
}

func parseConfig(raw string) (meshConfig, error) {
	var cfg meshConfig
	if err := json.Unmarshal([]byte(strings.TrimSpace(raw)), &cfg); err != nil {
		return cfg, fmt.Errorf("invalid config json: %w", err)
	}
	cfg.MeshID = strings.TrimSpace(cfg.MeshID)
	if cfg.MeshID == "" {
		cfg.MeshID = "edgez"
	}
	return cfg, nil
}

func privateKeyFromConfig(cfg meshConfig) (crypto.PrivKey, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(cfg.PrivateKey))
	if err != nil || len(raw) == 0 {
		return generateEd25519(bytes.NewReader(seedForConfig(cfg)))
	}
	if key, err := crypto.UnmarshalPrivateKey(raw); err == nil {
		return key, nil
	}
	if len(raw) == 32 {
		return generateEd25519(bytes.NewReader(raw))
	}
	return generateEd25519(bytes.NewReader(seedForConfig(cfg)))
}

func generateEd25519(reader *bytes.Reader) (crypto.PrivKey, error) {
	priv, _, err := crypto.GenerateEd25519Key(reader)
	return priv, err
}

func seedForConfig(cfg meshConfig) []byte {
	sum := sha256.Sum256([]byte(cfg.MeshID + "|" + cfg.Passphrase + "|" + cfg.PrivateKey))
	return sum[:]
}

func connectBootstrapPeers(ctx context.Context, h host.Host, peers []string) {
	for _, raw := range peers {
		addr := strings.TrimSpace(raw)
		if addr == "" {
			continue
		}
		ma, err := multiaddr.NewMultiaddr(addr)
		if err != nil {
			continue
		}
		info, err := peer.AddrInfoFromP2pAddr(ma)
		if err != nil {
			continue
		}
		go func(info *peer.AddrInfo) {
			connectCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			defer cancel()
			_ = h.Connect(connectCtx, *info)
		}(info)
	}
}

func listenAddrs(h host.Host) []string {
	out := make([]string, 0, len(h.Addrs()))
	for _, addr := range h.Addrs() {
		out = append(out, addr.String()+"/p2p/"+h.ID().String())
	}
	return out
}

func okJSON(extra map[string]any) string {
	extra["ok"] = true
	data, _ := json.Marshal(extra)
	return string(data)
}

func errorJSON(message string) string {
	data, _ := json.Marshal(map[string]any{"ok": false, "error": message})
	return string(data)
}
