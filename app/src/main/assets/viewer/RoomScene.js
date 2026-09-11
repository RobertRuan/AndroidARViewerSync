// RoomScene.js
// Procedurally-built enclosed room for orientation/sync verification.
// Exposes: window.RoomScene.build(scene)

import * as THREE from 'three';

export function buildRoom(scene) {
  const ROOM_W = 6, ROOM_D = 6, ROOM_H = 3;

  // -------- Skybox (visible through the window) --------
  function makeSkyTexture() {
    const c = document.createElement('canvas');
    c.width = 256; c.height = 256;
    const ctx = c.getContext('2d');
    const g = ctx.createLinearGradient(0, 0, 0, 256);
    g.addColorStop(0, '#0b1a3a');
    g.addColorStop(0.5, '#3a6ea5');
    g.addColorStop(1, '#a0c4e8');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, 256, 256);
    // sprinkle stars
    for (let i = 0; i < 60; i++) {
      ctx.fillStyle = 'rgba(255,255,255,' + (Math.random() * 0.8 + 0.2) + ')';
      ctx.fillRect(Math.random() * 256, Math.random() * 128, 1, 1);
    }
    const tex = new THREE.CanvasTexture(c);
    tex.mapping = THREE.EquirectangularReflectionMapping;
    return tex;
  }
  scene.background = null;       // transparent for AR overlay
  // We'll keep a visible skybox *behind* the walls via a large inverted cube
  const skyTex = makeSkyTexture();
  const skyGeo = new THREE.BoxGeometry(80, 80, 80);
  const skyMat = new THREE.MeshBasicMaterial({ map: skyTex, side: THREE.BackSide });
  const sky = new THREE.Mesh(skyGeo, skyMat);
  sky.name = 'skybox';
  scene.add(sky);

  // -------- Wood-like floor --------
  function makeWoodTexture() {
    const c = document.createElement('canvas');
    c.width = 256; c.height = 256;
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#7a4a23';
    ctx.fillRect(0, 0, 256, 256);
    for (let i = 0; i < 8; i++) {
      ctx.fillStyle = 'rgba(0,0,0,' + (0.05 + Math.random() * 0.1) + ')';
      ctx.fillRect(i * 32, 0, 1, 256);
    }
    for (let i = 0; i < 400; i++) {
      ctx.fillStyle = 'rgba(0,0,0,' + (Math.random() * 0.15) + ')';
      ctx.fillRect(Math.random() * 256, Math.random() * 256, 2, 1);
    }
    const t = new THREE.CanvasTexture(c);
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.repeat.set(4, 4);
    return t;
  }
  const floorMat = new THREE.MeshStandardMaterial({
    map: makeWoodTexture(),
    roughness: 0.7, metalness: 0.0
  });
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(ROOM_W, ROOM_D), floorMat);
  floor.rotation.x = -Math.PI / 2;
  floor.position.y = 0;
  floor.receiveShadow = true;
  floor.name = 'floor';
  scene.add(floor);

  // -------- Ceiling (white, slightly emissive so it's visible) --------
  const ceilMat = new THREE.MeshStandardMaterial({ color: 0xf5f5f5, roughness: 0.9 });
  const ceil = new THREE.Mesh(new THREE.PlaneGeometry(ROOM_W, ROOM_D), ceilMat);
  ceil.rotation.x = Math.PI / 2;
  ceil.position.y = ROOM_H;
  ceil.name = 'ceiling';
  scene.add(ceil);

  // -------- Walls (with one window on the +Z wall) --------
  const wallMat = new THREE.MeshStandardMaterial({ color: 0xdcdcd0, roughness: 0.95 });

  // Wall: -Z (back, behind camera at spawn)
  const wallBack = new THREE.Mesh(new THREE.BoxGeometry(ROOM_W, ROOM_H, 0.1), wallMat);
  wallBack.position.set(0, ROOM_H / 2, -ROOM_D / 2);
  scene.add(wallBack);

  // Wall: -X (left)
  const wallLeft = new THREE.Mesh(new THREE.BoxGeometry(0.1, ROOM_H, ROOM_D), wallMat);
  wallLeft.position.set(-ROOM_W / 2, ROOM_H / 2, 0);
  scene.add(wallLeft);

  // Wall: +X (right)
  const wallRight = new THREE.Mesh(new THREE.BoxGeometry(0.1, ROOM_H, ROOM_D), wallMat);
  wallRight.position.set(ROOM_W / 2, ROOM_H / 2, 0);
  scene.add(wallRight);

  // Wall: +Z (front) with a window cutout (Shape + hole + Extrude)
  const frontShape = new THREE.Shape();
  frontShape.moveTo(-ROOM_W / 2, 0);
  frontShape.lineTo(ROOM_W / 2, 0);
  frontShape.lineTo(ROOM_W / 2, ROOM_H);
  frontShape.lineTo(-ROOM_W / 2, ROOM_H);
  frontShape.lineTo(-ROOM_W / 2, 0);

  // window hole: 1.6 wide x 1.2 tall, centered horizontally, sill at 1.0
  const winW = 1.6, winH = 1.2, winSill = 1.0;
  const win = new THREE.Path();
  win.moveTo(-winW / 2, winSill);
  win.lineTo(winW / 2, winSill);
  win.lineTo(winW / 2, winSill + winH);
  win.lineTo(-winW / 2, winSill + winH);
  win.lineTo(-winW / 2, winSill);
  frontShape.holes.push(win);

  const frontGeo = new THREE.ExtrudeGeometry(frontShape, {
    depth: 0.1, bevelEnabled: false
  });
  // Extrude along +Z by default; rotate so it faces -Z (into room)
  const wallFront = new THREE.Mesh(frontGeo, wallMat);
  wallFront.position.set(0, 0, ROOM_D / 2);
  wallFront.rotation.y = Math.PI;       // face into room
  wallFront.name = 'wallFront';
  scene.add(wallFront);

  // Window frame (thin box around the hole, both sides)
  const frameMat = new THREE.MeshStandardMaterial({ color: 0x333333, roughness: 0.5 });
  const frameThickness = 0.06;
  // top
  const fTop = new THREE.Mesh(new THREE.BoxGeometry(winW + frameThickness * 2, frameThickness, 0.12), frameMat);
  fTop.position.set(0, winSill + winH + frameThickness / 2, ROOM_D / 2);
  scene.add(fTop);
  // bottom (sill)
  const fBottom = new THREE.Mesh(new THREE.BoxGeometry(winW + frameThickness * 2, frameThickness, 0.12), frameMat);
  fBottom.position.set(0, winSill - frameThickness / 2, ROOM_D / 2);
  scene.add(fBottom);
  // left
  const fLeft = new THREE.Mesh(new THREE.BoxGeometry(frameThickness, winH, 0.12), frameMat);
  fLeft.position.set(-winW / 2 - frameThickness / 2, winSill + winH / 2, ROOM_D / 2);
  scene.add(fLeft);
  // right
  const fRight = new THREE.Mesh(new THREE.BoxGeometry(frameThickness, winH, 0.12), frameMat);
  fRight.position.set(winW / 2 + frameThickness / 2, winSill + winH / 2, ROOM_D / 2);
  scene.add(fRight);
  // center mullion
  const fMid = new THREE.Mesh(new THREE.BoxGeometry(frameThickness, winH, 0.12), frameMat);
  fMid.position.set(0, winSill + winH / 2, ROOM_D / 2);
  scene.add(fMid);

  // -------- Furniture --------
  // Table (1.2 x 0.6 x 0.75)
  const tableMat = new THREE.MeshStandardMaterial({ color: 0x6b3f1a, roughness: 0.6 });
  const tableTop = new THREE.Mesh(new THREE.BoxGeometry(1.2, 0.05, 0.6), tableMat);
  tableTop.position.set(-1.0, 0.75, -1.0);
  tableTop.castShadow = tableTop.receiveShadow = true;
  scene.add(tableTop);
  for (const [dx, dz] of [[-0.55, -0.25], [0.55, -0.25], [-0.55, 0.25], [0.55, 0.25]]) {
    const leg = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.74, 0.06), tableMat);
    leg.position.set(-1.0 + dx, 0.37, -1.0 + dz);
    scene.add(leg);
  }

  // Chair (simple)
  function makeChair(x, z, ry) {
    const grp = new THREE.Group();
    const seatMat = new THREE.MeshStandardMaterial({ color: 0x444444, roughness: 0.7 });
    const seat = new THREE.Mesh(new THREE.BoxGeometry(0.45, 0.04, 0.45), seatMat);
    seat.position.y = 0.45;
    grp.add(seat);
    for (const [dx, dz] of [[-0.2, -0.2], [0.2, -0.2], [-0.2, 0.2], [0.2, 0.2]]) {
      const leg = new THREE.Mesh(new THREE.BoxGeometry(0.04, 0.45, 0.04), seatMat);
      leg.position.set(dx, 0.225, dz);
      grp.add(leg);
    }
    const back = new THREE.Mesh(new THREE.BoxGeometry(0.45, 0.5, 0.04), seatMat);
    back.position.set(0, 0.7, -0.22);
    grp.add(back);
    grp.position.set(x, 0, z);
    grp.rotation.y = ry;
    scene.add(grp);
  }
  makeChair(-0.4, -1.0, Math.PI / 2);
  makeChair(-1.6, -1.0, -Math.PI / 2);

  // Lamp (pole + shade + light)
  const lampGrp = new THREE.Group();
  const poleMat = new THREE.MeshStandardMaterial({ color: 0x222222, roughness: 0.4, metalness: 0.5 });
  const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.025, 0.025, 1.5, 12), poleMat);
  pole.position.y = 0.75;
  lampGrp.add(pole);
  const shade = new THREE.Mesh(
    new THREE.CylinderGeometry(0.18, 0.22, 0.25, 16, 1, true),
    new THREE.MeshStandardMaterial({ color: 0xfff2cc, side: THREE.DoubleSide, emissive: 0x553300, emissiveIntensity: 0.5 })
  );
  shade.position.y = 1.55;
  lampGrp.add(shade);
  lampGrp.position.set(2.2, 0, 2.2);
  scene.add(lampGrp);
  const lampLight = new THREE.PointLight(0xffd28a, 1.5, 6, 2);
  lampLight.position.set(2.2, 1.55, 2.2);
  lampLight.castShadow = true;
  scene.add(lampLight);

  // Rug
  const rug = new THREE.Mesh(
    new THREE.CircleGeometry(1.2, 32),
    new THREE.MeshStandardMaterial({ color: 0x8b3a3a, roughness: 0.9 })
  );
  rug.rotation.x = -Math.PI / 2;
  rug.position.set(0.5, 0.001, 0.5);
  rug.receiveShadow = true;
  scene.add(rug);

  // -------- Lighting --------
  const hemi = new THREE.HemisphereLight(0xffffff, 0x404040, 0.6);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xffffff, 0.8);
  sun.position.set(5, 8, 5);
  sun.castShadow = true;
  sun.shadow.mapSize.set(1024, 1024);
  scene.add(sun);
  const amb = new THREE.AmbientLight(0xffffff, 0.25);
  scene.add(amb);

  return { sky, floor };
}

// Hook for viewer.js
window.RoomScene = { build: buildRoom };
