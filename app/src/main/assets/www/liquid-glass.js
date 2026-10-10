/* ════════════════════════════════════════════════════════
   LIQUID GLASS BRIDGE (SYNC)
   Native Compose Backdrop (Kyant0/AndroidLiquidGlass)가
   실제 글래스를 그린다. 이 스크립트는:
   - 기존 검색/내비/미니바/팝업의 레이아웃 좌표를 브릿지로 전달
   - 하단 내비 Morphing Sliding Pill 위치 애니메이션만 유지
   app.js 의 재생/검색/AndroidBridge 로직은 건드리지 않는다.
════════════════════════════════════════════════════════ */
(function () {
    "use strict";

    const pressed = Object.create(null);

    function visibleSearchWrap() {
        return document.querySelector(".view.on .mob-srch-wrap");
    }

    function isOn(el) {
        return !!(el && el.classList.contains("on"));
    }

    function rectItem(id, kind, el, radius, extraVisible) {
        if (!el) return null;
        if (extraVisible === false) return null;
        const r = el.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) return null;
        const cs = getComputedStyle(el);
        if (cs.visibility === "hidden" || cs.display === "none") return null;
        return {
            id: id,
            k: kind,
            l: r.left,
            t: r.top,
            w: r.width,
            h: r.height,
            r: radius,
            p: !!pressed[id]
        };
    }

    function collect() {
        const items = [];
        const search = rectItem("search", "capsule", visibleSearchWrap(), 30);
        if (search) items.push(search);

        const nav = document.getElementById("mob-nav");
        const navItem = rectItem("nav", "capsule", nav, 30);
        if (navItem) items.push(navItem);

        const pill = document.getElementById("mnPill");
        if (pill && getComputedStyle(pill).display !== "none") {
            const p = rectItem("pill", "pill", pill, 999);
            if (p) items.push(p);
        }

        const bar = document.getElementById("bar");
        if (bar && !bar.classList.contains("bar-hidden")) {
            const b = rectItem("bar", "capsule", bar, 32);
            if (b) items.push(b);
        }

        const ctx = document.getElementById("pl-ctx-menu");
        if (isOn(ctx)) {
            const c = rectItem("ctx", "sheet", ctx, 20);
            if (c) items.push(c);
        }

        const dialog = document.getElementById("pl-dialog");
        if (dialog && isOn(dialog.closest("#pl-dialog-overlay"))) {
            const d = rectItem("dialog", "sheet", dialog, 28);
            if (d) items.push(d);
        }

        const confirm = document.getElementById("pl-confirm");
        if (confirm && isOn(confirm.closest("#pl-confirm-overlay"))) {
            const c = rectItem("confirm", "sheet", confirm, 28);
            if (c) items.push(c);
        }

        const addModal = document.getElementById("pl-add-modal");
        if (addModal && isOn(addModal.closest("#pl-add-modal-overlay"))) {
            const a = rectItem("addModal", "sheet", addModal, 30);
            if (a) items.push(a);
        }

        const aw = (window.AndroidBridge && typeof AndroidBridge.liquidGlassViewWidth === "function")
            ? (AndroidBridge.liquidGlassViewWidth() || window.innerWidth)
            : window.innerWidth;
        return {
            vw: window.innerWidth,
            vh: window.innerHeight,
            aw: aw,
            items: items
        };
    }

    let lastJson = "";
    function publish() {
        const payload = collect();
        const json = JSON.stringify(payload);
        if (json === lastJson) return;
        lastJson = json;
        if (window.AndroidBridge && typeof AndroidBridge.liquidGlassRegions === "function") {
            AndroidBridge.liquidGlassRegions(json);
        }
    }

    function bindPress(id, el) {
        if (!el || el._lgPressBound) return;
        el._lgPressBound = true;
        const down = () => { pressed[id] = true; publish(); };
        const up = () => { pressed[id] = false; publish(); };
        el.addEventListener("pointerdown", down, { passive: true });
        el.addEventListener("pointerup", up, { passive: true });
        el.addEventListener("pointercancel", up, { passive: true });
        el.addEventListener("pointerleave", up, { passive: true });
    }

    function bindPressTargets() {
        bindPress("search", visibleSearchWrap());
        bindPress("nav", document.getElementById("mob-nav"));
        bindPress("bar", document.getElementById("bar"));
        bindPress("ctx", document.getElementById("pl-ctx-menu"));
        bindPress("dialog", document.getElementById("pl-dialog"));
        bindPress("confirm", document.getElementById("pl-confirm"));
        bindPress("addModal", document.getElementById("pl-add-modal"));
        document.querySelectorAll(".mob-srch-wrap").forEach(el => bindPress("search", el));
    }

    /* ---------- 하단 내비 Morphing Sliding Pill (레이아웃만) ---------- */
    function morphPillTo(view) {
        const nav = document.getElementById("mob-nav");
        const pill = document.getElementById("mnPill");
        const btn = nav && nav.querySelector('.mn-btn[data-v="' + view + '"]');
        if (!nav || !pill || !btn) return;

        const navR = nav.getBoundingClientRect();
        const btnR = btn.getBoundingClientRect();
        const targetLeft = Math.round(btnR.left - navR.left + 3);
        const targetWidth = Math.round(btnR.width - 6);
        const prev = pill._rect;

        if (!prev) {
            pill.style.transition = "none";
            pill.style.left = targetLeft + "px";
            pill.style.width = targetWidth + "px";
            void pill.offsetWidth;
            pill.style.transition = "";
        } else if (prev.left !== targetLeft) {
            const stretchLeft = Math.min(prev.left, targetLeft);
            const stretchRight = Math.max(prev.left + prev.width, targetLeft + targetWidth);
            pill.style.transitionProperty = "left, width";
            pill.style.transitionDuration = ".15s";
            pill.style.transitionTimingFunction = "cubic-bezier(.3,.9,.4,1)";
            pill.style.left = stretchLeft + "px";
            pill.style.width = (stretchRight - stretchLeft) + "px";

            clearTimeout(pill._morphTimer);
            pill._morphTimer = setTimeout(() => {
                pill.style.transitionDuration = ".48s";
                pill.style.transitionTimingFunction = "cubic-bezier(.22,1.61,.36,1)";
                pill.style.left = targetLeft + "px";
                pill.style.width = targetWidth + "px";
            }, 150);
        }
        pill._rect = { left: targetLeft, width: targetWidth };
    }

    function currentActiveView() {
        const on = document.querySelector(".mn-btn.on");
        return on ? on.dataset.v : "home";
    }

    function wireNavHook() {
        if (typeof window.gv !== "function") {
            setTimeout(wireNavHook, 50);
            return;
        }
        const originalGv = window.gv;
        window.gv = function (v, el) {
            originalGv(v, el);
            morphPillTo(v);
            bindPressTargets();
            publish();
        };
    }

    let resizeTimer;
    addEventListener("resize", () => {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => {
            morphPillTo(currentActiveView());
            publish();
        }, 160);
    });

    function loop() {
        publish();
        requestAnimationFrame(loop);
    }

    function boot() {
        morphPillTo(currentActiveView());
        bindPressTargets();
        wireNavHook();
        publish();
        requestAnimationFrame(loop);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot);
    } else {
        boot();
    }
})();
