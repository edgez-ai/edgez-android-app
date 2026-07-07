package main

/*
#cgo android LDFLAGS: -llog
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <stdio.h>
#endif

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

static void edgez_log_print(int priority, const char* tag, const char* message) {
	if (message == NULL || tag == NULL) {
		return;
	}
#ifdef __ANDROID__
	__android_log_print(priority, tag, "%s", message);
#else
	fprintf(stderr, "[%s] %s\n", tag, message);
#endif
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
	"net"
	"strings"
	"sync"
	"time"
	"unicode"
	"unsafe"

	libp2p "github.com/libp2p/go-libp2p"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p-pubsub"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/p2p/discovery/mdns"
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
	mdns   mdns.Service
}

var (
	stateMu      sync.Mutex
	state        *meshState
	callbackMu   sync.RWMutex
	callbackObj  C.jobject
	callbackFunc C.jmethodID
)

const (
	logTag        = "EdgeZLibp2pNative"
	logDebugLevel = C.int(3)
	logInfoLevel  = C.int(4)
	logWarnLevel  = C.int(5)
)

func logCat(level C.int, tag string, msg string) {
	cTag := C.CString(tag)
	cMsg := C.CString(msg)
	defer C.free(unsafe.Pointer(cTag))
	defer C.free(unsafe.Pointer(cMsg))
	C.edgez_log_print(level, cTag, cMsg)
}

func logDebug(tag string, msg string) {
	logCat(logDebugLevel, logTag, fmt.Sprintf("[%s] %s", tag, msg))
}

func logInfo(tag string, msg string) {
	logCat(logInfoLevel, logTag, fmt.Sprintf("[%s] %s", tag, msg))
}

func logWarn(tag string, msg string) {
	logCat(logWarnLevel, logTag, fmt.Sprintf("[%s] %s", tag, msg))
}

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
		libp2p.AddrsFactory(rewriteListenerAddrs()),
		libp2p.EnableRelay(),
		libp2p.EnableHolePunching(),
		libp2p.EnableAutoNATv2(),
		libp2p.NATPortMap(),
	)
	if err != nil {
		cancel()
		return errorJSON(fmt.Sprintf("start libp2p host: %v", err))
	}

	defaultBootstrapPeers := dht.GetDefaultBootstrapPeerAddrInfos()
	meshBootstrapPeers := append(defaultBootstrapPeers, parseBootstrapPeers(cfg.BootstrapPeers)...)
	logDebug("start", fmt.Sprintf("mesh config mesh_id=%s topic=%s listen=%s custom_bootstraps=%d dht_bootstraps=%d", cfg.MeshID, cfg.Topic, cfg.Listen, len(cfg.BootstrapPeers), len(defaultBootstrapPeers)))
	if len(cfg.BootstrapPeers) > 0 {
		logInfo("bootstrap", fmt.Sprintf("using custom bootstrap peers=%d", len(cfg.BootstrapPeers)))
	}
	meshBootstrapPeers = dedupePeerInfo(meshBootstrapPeers)
	kad, err := dht.New(ctx, h, dht.Mode(dht.ModeAuto), dht.BootstrapPeers(meshBootstrapPeers...))
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
	mdnsService, err := startMdnsDiscovery(ctx, h, cfg.MeshID)
	if err != nil {
		logWarn("mdns", fmt.Sprintf("mdns discovery start failed: %v", err))
	}

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

	state = &meshState{ctx: ctx, cancel: cancel, host: h, dht: kad, pubsub: ps, topic: topic, sub: sub, peerID: h.ID(), mdns: mdnsService}
	logInfo("start", fmt.Sprintf("running mesh_id=%s peer=%s topic=%s", cfg.MeshID, h.ID().String(), topicName))
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
	logInfo("stop", "stopping libp2p mesh")
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
	if state.mdns != nil {
		_ = state.mdns.Close()
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
		logWarn("publish", "publish failed: libp2p mesh is not running")
		return errorJSON("libp2p mesh is not running")
	}
	logDebug("publish", fmt.Sprintf("sending pubsub message bytes=%d", len(payload)))
	if err := local.topic.Publish(local.ctx, payload); err != nil {
		logWarn("publish", fmt.Sprintf("publish failed bytes=%d err=%v", len(payload), err))
		return errorJSON(fmt.Sprintf("publish: %v", err))
	}
	logDebug("publish", fmt.Sprintf("publish success bytes=%d", len(payload)))
	return okJSON(map[string]any{"state": "published", "bytes": len(payload)})
}

func readLoop(local *meshState) {
	for {
		msg, err := local.sub.Next(local.ctx)
		if err != nil {
			logWarn("readLoop", fmt.Sprintf("subscription end: %v", err))
			return
		}
		logDebug("readLoop", fmt.Sprintf("received pubsub message from=%s bytes=%d", msg.ReceivedFrom.String(), len(msg.Data)))
		if msg.ReceivedFrom == local.peerID {
			logDebug("readLoop", "skip self message")
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
	logDebug("callback", fmt.Sprintf("dispatching payload bytes=%d", len(payload)))
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
	for _, info := range parseBootstrapPeers(peers) {
		logInfo("bootstrap", fmt.Sprintf("connect bootstrap=%s", info.String()))
		go func(info peer.AddrInfo) {
			connectCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			defer cancel()
			if err := h.Connect(connectCtx, info); err != nil {
				logWarn("bootstrap", fmt.Sprintf("connect bootstrap failed=%s err=%v", info.ID, err))
			} else {
				logInfo("bootstrap", fmt.Sprintf("connected bootstrap=%s", info.ID))
			}
		}(info)
	}
}

func parseBootstrapPeers(peers []string) []peer.AddrInfo {
	out := make([]peer.AddrInfo, 0, len(peers))
	seen := map[string]struct{}{}
	for _, raw := range peers {
		addr := strings.TrimSpace(raw)
		if addr == "" {
			continue
		}
		ma, err := multiaddr.NewMultiaddr(addr)
		if err != nil {
			logWarn("bootstrap", fmt.Sprintf("invalid bootstrap multiaddr=%s err=%v", addr, err))
			continue
		}
		info, err := peer.AddrInfoFromP2pAddr(ma)
		if err != nil {
			logWarn("bootstrap", fmt.Sprintf("invalid bootstrap addrinfo=%s err=%v", addr, err))
			continue
		}
		if _, exists := seen[info.ID.String()]; exists {
			continue
		}
		seen[info.ID.String()] = struct{}{}
		out = append(out, *info)
	}
	return out
}

func dedupePeerInfo(peers []peer.AddrInfo) []peer.AddrInfo {
	unique := make(map[string]peer.AddrInfo, len(peers))
	for _, peer := range peers {
		if _, exists := unique[peer.ID.String()]; exists {
			continue
		}
		unique[peer.ID.String()] = peer
	}
	out := make([]peer.AddrInfo, 0, len(unique))
	for _, info := range unique {
		out = append(out, info)
	}
	return out
}

func startMdnsDiscovery(ctx context.Context, h host.Host, meshID string) (mdns.Service, error) {
	notifee := &meshMdnsNotifee{
		ctx:  ctx,
		host: h,
	}
	serviceName := (&mdnsServiceName{meshID: meshID}).String()
	service := mdns.NewMdnsService(h, serviceName, notifee)
	if err := service.Start(); err != nil {
		return nil, err
	}
	logInfo("mdns", fmt.Sprintf("started service=%s mesh=%s", serviceName, meshID))
	return service, nil
}

type meshMdnsNotifee struct {
	ctx  context.Context
	host host.Host
}

func (notifee *meshMdnsNotifee) HandlePeerFound(info peer.AddrInfo) {
	if info.ID == notifee.host.ID() {
		return
	}
	logInfo("mdns", fmt.Sprintf("discovered peer=%s addrs=%d", info.ID, len(info.Addrs)))
	connectCtx, cancel := context.WithTimeout(notifee.ctx, 10*time.Second)
	defer cancel()
	if err := notifee.host.Connect(connectCtx, info); err != nil {
		logWarn("mdns", fmt.Sprintf("connect discovered peer=%s err=%v", info.ID, err))
	} else {
		logInfo("mdns", fmt.Sprintf("connected discovered peer=%s", info.ID))
	}
}

type mdnsServiceName struct {
	meshID string
}

func (name mdnsServiceName) String() string {
	base := strings.TrimSpace(strings.ToLower(name.meshID))
	if base == "" {
		base = "edgez"
	}
	var sanitized []rune
	for _, char := range base {
		switch {
		case char >= 'a' && char <= 'z':
			sanitized = append(sanitized, char)
		case char >= '0' && char <= '9':
			sanitized = append(sanitized, char)
		case char == '-':
			sanitized = append(sanitized, char)
		case char == ' ' || char == '_':
			sanitized = append(sanitized, '-')
		default:
			if unicode.IsLetter(char) {
				sanitized = append(sanitized, unicode.ToLower(char))
			}
		}
	}
	if len(sanitized) == 0 {
		sanitized = []rune("mesh")
	}
	if len(sanitized) > 30 {
		sanitized = sanitized[:30]
	}
	return "_edgez-" + string(sanitized) + "._udp"
}

func listenAddrs(h host.Host) []string {
	out := make([]string, 0, len(h.Addrs()))
	for _, addr := range h.Addrs() {
		out = append(out, addr.String()+"/p2p/"+h.ID().String())
	}
	return out
}

func localIPv4Addrs() []string {
	interfaces, err := net.Interfaces()
	if err != nil {
		logWarn("net", fmt.Sprintf("interface list failed: %v", err))
		return nil
	}

	out := make([]string, 0)
	seen := map[string]struct{}{}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, raw := range addrs {
			ipStr := raw.String()
			ip, _, err := net.ParseCIDR(ipStr)
			if err != nil {
				if parsed := net.ParseIP(ipStr); parsed != nil {
					ip = parsed
				} else {
					continue
				}
			}
			ipv4 := ip.To4()
			if ipv4 == nil || ipv4.IsLoopback() || ipv4.IsUnspecified() {
				continue
			}
			ipText := ipv4.String()
			if _, exists := seen[ipText]; exists {
				continue
			}
			seen[ipText] = struct{}{}
			out = append(out, ipText)
		}
	}
	return out
}

func rewriteListenerAddrs() func([]multiaddr.Multiaddr) []multiaddr.Multiaddr {
	localIps := localIPv4Addrs()
	return func(addrs []multiaddr.Multiaddr) []multiaddr.Multiaddr {
		if len(localIps) == 0 {
			return addrs
		}

		out := make([]multiaddr.Multiaddr, 0, len(addrs)*len(localIps))
		seen := map[string]struct{}{}
		appendAddr := func(addr string) {
			ma, err := multiaddr.NewMultiaddr(addr)
			if err != nil {
				logWarn("net", fmt.Sprintf("invalid rewritten addr=%s err=%v", addr, err))
				return
			}
			key := ma.String()
			if _, exists := seen[key]; exists {
				return
			}
			seen[key] = struct{}{}
			out = append(out, ma)
		}

		for _, addr := range addrs {
			raw := addr.String()
			if strings.HasPrefix(raw, "/ip4/0.0.0.0/") {
				for _, ip := range localIps {
					appendAddr("/ip4/" + ip + strings.TrimPrefix(raw, "/ip4/0.0.0.0"))
				}
				continue
			}
			if strings.HasPrefix(raw, "/ip6/::/") && len(localIps) == 0 {
				continue
			}
			appendAddr(raw)
		}

		if len(out) == 0 {
			return addrs
		}
		return out
	}
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
