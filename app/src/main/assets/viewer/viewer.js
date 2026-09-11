// viewer.js
// Main Three.js viewer: AR-style transparent overlay scene.
// Bridge API (called from native Kotlin via WebView.evaluateJavascript):
//   window.__setOrientation(yawDeg, pitchDeg, rollDeg, rollCompOn)
//   window.__setFov(deg)
//   window.__loadGltfUrl(url)
//   window.__resetView()

import * as THREE from 'three';
import { GLTFLoader } from './GLTFLoader.module.js';
import { buildRoom } from './RoomScene.js';

let renderer, scene, camera, clock;
let gltfLoader = null;
let roomRoot = null;            // group holding the procedural room (so we can remove it on custom model load)
let modelRoot = null;           // group holding the loaded GLTF
let dirty = true;               // requestAnimationFrame re-render gate (battery)
let lastFpsAt = 0, frames = 0, fps = 0;
let bridgeReady = false;

// Smoothed target state (the bridge pushes raw angles; we lerp on the GPU side as a second-stage smoother)
const target = { yaw: 0, pitch: 0, roll: 0, rollCompOn: 1, fov: 75 };

const errEl = () => document.getElementById('err');
function showError(msg) {
  const el = errEl();
  if (el) { el.textContent = String(msg); el.style.display = 'block'; }
}

function init() {
  try {
    const container = document.getElementById('app');
    renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, premultipliedAlpha: false });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.setClearColor(0x000000, 0);   // fully transparent → camera preview shows through
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    container.appendChild(renderer.domElement);

    scene = new THREE.Scene();
    scene.background = null;

    camera = new THREE.PerspectiveCamera(target.fov, window.innerWidth / window.innerHeight, 0.05, 200);
    // Eye height 1.6 m, centered in the room, facing -Z (toward the back wall with no window)
    camera.position.set(0, 1.6, 0);
    camera.rotation.order = 'YXZ';          // yaw, then pitch, then roll
    scene.add(camera);

    clock = new THREE.Clock();

    // Build the default procedural room
    roomRoot = new THREE.Group();
    roomRoot.name = 'roomRoot';
    buildRoom(roomRoot);
    scene.add(roomRoot);

    gltfLoader = new GLTFLoader();

    // Handle resize
    window.addEventListener('resize', onResize, false);

    // Mark ready, let native know
    bridgeReady = true;
    window.__viewerReady = true;
    if (window.NativeBridge && typeof window.NativeBridge.onReady === 'function') {
      window.NativeBridge.onReady();
    }

    requestAnimationFrame(loop);
  } catch (e) {
    showError('init() failed: ' + (e && e.stack || e));
  }
}

function onResize() {
  if (!renderer || !camera) return;
  const w = window.innerWidth, h = window.innerHeight;
  renderer.setSize(w, h);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  dirty = true;
}

function loop() {
  requestAnimationFrame(loop);
  if (!renderer || !scene || !camera) return;

  // FPS counter
  frames++;
  const now = performance.now();
  if (now - lastFpsAt >= 500) {
    fps = Math.round(frames * 1000 / (now - lastFpsAt));
    frames = 0;
    lastFpsAt = now;
    if (window.NativeBridge && typeof window.NativeBridge.onFps === 'function') {
      window.NativeBridge.onFps(fps);
    }
    dirty = true;     // make sure HUD gets updates
  }

  // Second-stage smoothing (complementary to native low-pass): lerp current rotation toward target
  // This further kills residual jitter from sensor sampling noise.
  const k = 0.35;     // closer to 1 = snappier, closer to 0 = smoother but laggier
  const cy = camera.rotation.y;
  const cp = camera.rotation.x;
  const cr = camera.rotation.z;
  const targetYaw   = THREE.MathUtils.degToRad(-target.yaw);
  const targetPitch = THREE.MathUtils.degToRad(-target.pitch);
  const targetRoll  = target.rollCompOn ? 0 : THREE.MathUtils.degToRad(-target.roll);
  // shortest-path yaw delta
  let dy = targetYaw - cy;
  while (dy > Math.PI) dy -= 2 * Math.PI;
  while (dy < -Math.PI) dy += 2 * Math.PI;
  let dp = targetPitch - cp;
  let dr = targetRoll - cr;
  // ignore tiny sub-pixel deltas (deadzone at JS level too)
  if (Math.abs(dy) < 0.0003) dy = 0;
  if (Math.abs(dp) < 0.0003) dp = 0;
  if (Math.abs(dr) < 0.0003) dr = 0;
  if (dy !== 0 || dp !== 0 || dr !== 0) {
    camera.rotation.y = cy + dy * k;
    camera.rotation.x = cp + dp * k;
    camera.rotation.z = cr + dr * k;
    dirty = true;
  }

  // FOV smooth
  if (Math.abs(camera.fov - target.fov) > 0.01) {
    camera.fov += (target.fov - camera.fov) * 0.25;
    camera.updateProjectionMatrix();
    dirty = true;
  }

  if (dirty) {
    renderer.render(scene, camera);
    dirty = false;
  }
}

// ---------- Bridge API ----------

window.__setOrientation = function (yawDeg, pitchDeg, rollDeg, rollCompOn) {
  if (!bridgeReady) return;
  target.yaw = yawDeg;
  target.pitch = pitchDeg;
  target.roll = rollDeg;
  target.rollCompOn = rollCompOn ? 1 : 0;
  dirty = true;
};

window.__setFov = function (deg) {
  // clamp 20..110
  target.fov = Math.min(110, Math.max(20, deg));
  dirty = true;
};

window.__loadGltfUrl = function (url) {
  if (!gltfLoader) { showError('GLTFLoader not initialised'); return; }
  // Remove existing custom model and the procedural room
  if (modelRoot) {
    scene.remove(modelRoot);
    modelRoot = null;
  }
  if (roomRoot) {
    scene.remove(roomRoot);
    roomRoot = null;
  }
  // Keep an invisible ground plane so models without their own floor still have a reference
  if (!scene.getObjectByName('fallbackFloor')) {
    const f = new THREE.Mesh(
      new THREE.PlaneGeometry(20, 20),
      new THREE.MeshStandardMaterial({ color: 0x333333, roughness: 1.0, transparent: true, opacity: 0.0 })
    );
    f.rotation.x = -Math.PI / 2;
    f.position.y = 0;
    f.name = 'fallbackFloor';
    scene.add(f);
  }
  gltfLoader.load(
    url,
    (gltf) => {
      modelRoot = gltf.scene;
      modelRoot.name = 'customModel';
      // Auto-center & drop to floor
      const box = new THREE.Box3().setFromObject(modelRoot);
      const size = box.getSize(new THREE.Vector3());
      const center = box.getCenter(new THREE.Vector3());
      const maxDim = Math.max(size.x, size.y, size.z);
      const scale = maxDim > 0 ? 3.0 / maxDim : 1.0;   // normalize to ~3m tall
      modelRoot.scale.setScalar(scale);
      // recompute after scale
      const box2 = new THREE.Box3().setFromObject(modelRoot);
      const c2 = box2.getCenter(new THREE.Vector3());
      modelRoot.position.x -= c2.x;
      modelRoot.position.z -= c2.z;
      modelRoot.position.y -= box2.min.y;   // sit on floor
      scene.add(modelRoot);
      dirty = true;
      if (window.NativeBridge && typeof window.NativeBridge.onModelLoaded === 'function') {
        window.NativeBridge.onModelLoaded(String(url));
      }
    },
    undefined,
    (err) => {
      showError('GLTF load failed: ' + (err && err.message ? err.message : err));
      if (window.NativeBridge && typeof window.NativeBridge.onModelError === 'function') {
        window.NativeBridge.onModelError(String(err && err.message ? err.message : err));
      }
    }
  );
};

window.__resetView = function () {
  target.yaw = 0;
  target.pitch = 0;
  target.roll = 0;
  target.fov = 75;
  camera.position.set(0, 1.6, 0);
  camera.rotation.set(0, 0, 0);
  dirty = true;
};

// expose fps target for native HUD reads
window.__getFps = function () { return fps; };

// boot
if (document.readyState === 'complete' || document.readyState === 'interactive') {
  init();
} else {
  window.addEventListener('DOMContentLoaded', init, false);
}
