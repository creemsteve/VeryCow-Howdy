# Rust dependency licenses

Resolved root Cargo.lock packages, including optional/platform/test dependencies. Runtime selection depends on target and enabled features. The VCH Flip JNI library has its own resolution in `android/native/Cargo.lock` and depends on the current root crate with `neural` enabled and desktop/audio backends disabled. Source and license texts are in the corresponding Cargo registry packages.

| Package | Version | Declared license |
| --- | --- | --- |
| ab_glyph | 0.2.32 | Apache-2.0 |
| ab_glyph_rasterizer | 0.1.10 | Apache-2.0 |
| accesskit | 0.16.3 | MIT OR Apache-2.0 |
| accesskit_atspi_common | 0.9.3 | MIT OR Apache-2.0 |
| accesskit_consumer | 0.24.3 | MIT OR Apache-2.0 |
| accesskit_macos | 0.17.4 | MIT OR Apache-2.0 |
| accesskit_unix | 0.12.3 | MIT OR Apache-2.0 |
| accesskit_windows | 0.23.2 | MIT OR Apache-2.0 |
| accesskit_winit | 0.22.4 | Apache-2.0 |
| adler2 | 2.0.1 | 0BSD OR MIT OR Apache-2.0 |
| aead | 0.5.2 | MIT OR Apache-2.0 |
| aes | 0.8.4 | MIT OR Apache-2.0 |
| aes-gcm | 0.10.3 | Apache-2.0 OR MIT |
| ahash | 0.8.12 | MIT OR Apache-2.0 |
| aho-corasick | 1.1.5 | Unlicense OR MIT |
| alsa | 0.9.1 | Apache-2.0/MIT |
| alsa-sys | 0.3.1 | MIT |
| android-activity | 0.6.1 | MIT OR Apache-2.0 |
| android-properties | 0.2.2 | MIT |
| android_system_properties | 0.1.6 | MIT OR Apache-2.0 |
| annotate-snippets | 0.11.5 | MIT OR Apache-2.0 |
| anstream | 1.0.0 | MIT OR Apache-2.0 |
| anstyle | 1.0.14 | MIT OR Apache-2.0 |
| anstyle-parse | 1.0.0 | MIT OR Apache-2.0 |
| anstyle-query | 1.1.5 | MIT OR Apache-2.0 |
| anstyle-wincon | 3.0.11 | MIT OR Apache-2.0 |
| anyhow | 1.0.104 | MIT OR Apache-2.0 |
| arboard | 3.7.0 | MIT OR Apache-2.0 |
| argon2 | 0.5.3 | MIT OR Apache-2.0 |
| arrayref | 0.3.9 | BSD-2-Clause |
| arrayvec | 0.7.8 | MIT OR Apache-2.0 |
| as-raw-xcb-connection | 1.0.1 | MIT OR Apache-2.0 |
| ash | 0.38.0+1.3.281 | MIT OR Apache-2.0 |
| ashpd | 0.14.0 | MIT |
| ashpd | 0.8.2 | MIT |
| asn1-rs | 0.7.2 | MIT OR Apache-2.0 |
| asn1-rs-derive | 0.6.0 | MIT OR Apache-2.0 |
| asn1-rs-impl | 0.2.0 | MIT/Apache-2.0 |
| async-broadcast | 0.7.2 | MIT OR Apache-2.0 |
| async-channel | 2.5.0 | Apache-2.0 OR MIT |
| async-executor | 1.14.1 | Apache-2.0 OR MIT |
| async-fs | 2.2.0 | Apache-2.0 OR MIT |
| async-io | 2.6.0 | Apache-2.0 OR MIT |
| async-lock | 3.5.0 | Apache-2.0 OR MIT |
| async-net | 2.0.0 | Apache-2.0 OR MIT |
| async-process | 2.5.0 | Apache-2.0 OR MIT |
| async-recursion | 1.2.0 | MIT OR Apache-2.0 |
| async-signal | 0.2.14 | Apache-2.0 OR MIT |
| async-task | 4.7.1 | Apache-2.0 OR MIT |
| async-trait | 0.1.92 | MIT OR Apache-2.0 |
| atomic-waker | 1.1.2 | Apache-2.0 OR MIT |
| atspi | 0.22.0 | Apache-2.0 OR MIT |
| atspi-common | 0.6.0 | Apache-2.0 OR MIT |
| atspi-connection | 0.6.0 | Apache-2.0 OR MIT |
| atspi-proxies | 0.6.0 | Apache-2.0 OR MIT |
| autocfg | 1.5.1 | Apache-2.0 OR MIT |
| base64 | 0.22.1 | MIT OR Apache-2.0 |
| base64ct | 1.8.3 | Apache-2.0 OR MIT |
| bindgen | 0.65.1 | BSD-3-Clause |
| bindgen | 0.72.1 | BSD-3-Clause |
| bit-set | 0.6.0 | MIT/Apache-2.0 |
| bit-vec | 0.7.0 | MIT/Apache-2.0 |
| bitflags | 1.3.2 | MIT/Apache-2.0 |
| bitflags | 2.13.2 | MIT OR Apache-2.0 |
| blake2 | 0.10.6 | MIT OR Apache-2.0 |
| block | 0.1.6 | MIT |
| block-buffer | 0.10.4 | MIT OR Apache-2.0 |
| block-padding | 0.3.3 | MIT OR Apache-2.0 |
| block2 | 0.5.1 | MIT |
| block2 | 0.6.2 | MIT |
| blocking | 1.7.0 | Apache-2.0 OR MIT |
| bumpalo | 3.20.3 | MIT OR Apache-2.0 |
| bytemuck | 1.25.2 | Zlib OR Apache-2.0 OR MIT |
| bytemuck_derive | 1.12.1 | Zlib OR Apache-2.0 OR MIT |
| byteorder-lite | 0.1.0 | Unlicense OR MIT |
| bytes | 1.12.1 | MIT |
| calloop | 0.13.0 | MIT |
| calloop | 0.14.5 | MIT |
| calloop-wayland-source | 0.3.0 | MIT |
| calloop-wayland-source | 0.4.1 | MIT |
| cbc | 0.1.2 | MIT OR Apache-2.0 |
| cc | 1.11.1 | MIT OR Apache-2.0 |
| cesu8 | 1.1.0 | Apache-2.0/MIT |
| cexpr | 0.6.0 | Apache-2.0/MIT |
| cfg-expr | 0.20.10 | MIT OR Apache-2.0 |
| cfg-if | 0.1.10 | MIT/Apache-2.0 |
| cfg-if | 1.0.5 | MIT OR Apache-2.0 |
| cfg_aliases | 0.1.1 | MIT |
| cfg_aliases | 0.2.2 | MIT |
| cgl | 0.3.2 | MIT / Apache-2.0 |
| chacha20 | 0.10.2 | MIT OR Apache-2.0 |
| cipher | 0.4.4 | MIT OR Apache-2.0 |
| clang-sys | 1.9.1 | Apache-2.0 |
| clap | 4.6.7 | MIT OR Apache-2.0 |
| clap_builder | 4.6.7 | MIT OR Apache-2.0 |
| clap_derive | 4.6.7 | MIT OR Apache-2.0 |
| clap_lex | 1.1.1 | MIT OR Apache-2.0 |
| clipboard-win | 5.4.3 | BSL-1.0 |
| cmov | 0.5.4 | Apache-2.0 OR MIT |
| cms | 0.2.3 | Apache-2.0 OR MIT |
| cocoa | 0.20.2 | MIT / Apache-2.0 |
| cocoa-foundation | 0.2.1 | MIT OR Apache-2.0 |
| codespan-reporting | 0.11.1 | Apache-2.0 |
| colorchoice | 1.0.5 | MIT OR Apache-2.0 |
| com | 0.6.0 | MIT |
| com_macros | 0.6.0 | MIT |
| com_macros_support | 0.6.0 | MIT |
| combine | 4.6.9 | MIT |
| concurrent-queue | 2.5.0 | Apache-2.0 OR MIT |
| const-oid | 0.10.2 | Apache-2.0 OR MIT |
| const-oid | 0.9.6 | Apache-2.0 OR MIT |
| cookie-factory | 0.3.3 | MIT |
| core-foundation | 0.10.1 | MIT OR Apache-2.0 |
| core-foundation | 0.7.0 | MIT / Apache-2.0 |
| core-foundation | 0.9.4 | MIT OR Apache-2.0 |
| core-foundation-sys | 0.7.0 | MIT / Apache-2.0 |
| core-foundation-sys | 0.8.7 | MIT OR Apache-2.0 |
| core-graphics | 0.19.2 | MIT / Apache-2.0 |
| core-graphics | 0.23.2 | MIT OR Apache-2.0 |
| core-graphics-types | 0.1.3 | MIT OR Apache-2.0 |
| core-graphics-types | 0.2.0 | MIT OR Apache-2.0 |
| core-media-sys | 0.1.2 | MIT |
| core-video-sys | 0.1.4 | MIT |
| coreaudio-rs | 0.11.3 | MIT/Apache-2.0 |
| coreaudio-sys | 0.2.18 | MIT |
| cpal | 0.15.3 | Apache-2.0 |
| cpufeatures | 0.2.17 | MIT OR Apache-2.0 |
| cpufeatures | 0.3.1 | MIT OR Apache-2.0 |
| crc32fast | 1.5.4 | MIT OR Apache-2.0 |
| crossbeam-utils | 0.8.23 | MIT OR Apache-2.0 |
| crypto-common | 0.1.7 | MIT OR Apache-2.0 |
| crypto-common | 0.2.2 | MIT OR Apache-2.0 |
| ctr | 0.9.2 | MIT OR Apache-2.0 |
| ctrlc | 3.5.2 | MIT/Apache-2.0 |
| ctutils | 0.4.8 | Apache-2.0 OR MIT |
| cursor-icon | 1.2.0 | MIT OR Apache-2.0 OR Zlib |
| dasp_sample | 0.11.0 | MIT OR Apache-2.0 |
| data-encoding | 2.11.1 | MIT |
| der | 0.7.10 | Apache-2.0 OR MIT |
| der | 0.8.3 | Apache-2.0 OR MIT |
| der-parser | 10.0.0 | MIT OR Apache-2.0 |
| der_derive | 0.7.3 | Apache-2.0 OR MIT |
| deranged | 0.5.9 | MIT OR Apache-2.0 |
| des | 0.8.1 | MIT OR Apache-2.0 |
| digest | 0.10.7 | MIT OR Apache-2.0 |
| digest | 0.11.3 | MIT OR Apache-2.0 |
| dispatch | 0.2.0 | MIT |
| dispatch2 | 0.3.1 | Zlib OR Apache-2.0 OR MIT |
| displaydoc | 0.2.7 | MIT OR Apache-2.0 |
| dlib | 0.5.3 | MIT |
| document-features | 0.2.12 | MIT OR Apache-2.0 |
| downcast-rs | 1.2.1 | MIT/Apache-2.0 |
| dpi | 0.1.2 | Apache-2.0 AND MIT |
| drm | 0.14.1 | MIT |
| drm | 0.15.0 | MIT |
| drm-ffi | 0.9.1 | MIT |
| drm-fourcc | 2.2.0 | MIT |
| drm-sys | 0.8.1 | MIT |
| ecolor | 0.29.1 | MIT OR Apache-2.0 |
| eframe | 0.29.1 | MIT OR Apache-2.0 |
| egui | 0.29.1 | MIT OR Apache-2.0 |
| egui-wgpu | 0.29.1 | MIT OR Apache-2.0 |
| egui-winit | 0.29.1 | MIT OR Apache-2.0 |
| egui_glow | 0.29.1 | MIT OR Apache-2.0 |
| either | 1.19.0 | MIT OR Apache-2.0 |
| emath | 0.29.1 | MIT OR Apache-2.0 |
| endi | 1.1.1 | MIT |
| enumflags2 | 0.7.12 | MIT OR Apache-2.0 |
| enumflags2_derive | 0.7.12 | MIT OR Apache-2.0 |
| epaint | 0.29.1 | MIT OR Apache-2.0 |
| epaint_default_fonts | 0.29.1 | (MIT OR Apache-2.0) AND OFL-1.1 AND LicenseRef-UFL-1.0 |
| equivalent | 1.0.3 | Apache-2.0 OR MIT |
| errno | 0.3.14 | MIT OR Apache-2.0 |
| error-code | 3.4.0 | BSL-1.0 |
| event-listener | 5.4.2 | Apache-2.0 OR MIT |
| event-listener-strategy | 0.5.4 | Apache-2.0 OR MIT |
| fastrand | 2.5.0 | Apache-2.0 OR MIT |
| fdeflate | 0.3.7 | MIT OR Apache-2.0 |
| find-msvc-tools | 0.1.15 | MIT OR Apache-2.0 |
| flagset | 0.4.7 | Apache-2.0 |
| flate2 | 1.1.11 | MIT OR Apache-2.0 |
| flume | 0.11.1 | Apache-2.0/MIT |
| foldhash | 0.1.5 | Zlib |
| foreign-types | 0.3.2 | MIT/Apache-2.0 |
| foreign-types | 0.5.0 | MIT/Apache-2.0 |
| foreign-types-macros | 0.2.4 | MIT/Apache-2.0 |
| foreign-types-shared | 0.1.1 | MIT/Apache-2.0 |
| foreign-types-shared | 0.3.1 | MIT/Apache-2.0 |
| form_urlencoded | 1.2.2 | MIT OR Apache-2.0 |
| fs2 | 0.4.3 | MIT/Apache-2.0 |
| futures-channel | 0.3.34 | MIT OR Apache-2.0 |
| futures-core | 0.3.34 | MIT OR Apache-2.0 |
| futures-io | 0.3.34 | MIT OR Apache-2.0 |
| futures-lite | 2.6.1 | Apache-2.0 OR MIT |
| futures-macro | 0.3.34 | MIT OR Apache-2.0 |
| futures-sink | 0.3.34 | MIT OR Apache-2.0 |
| futures-task | 0.3.34 | MIT OR Apache-2.0 |
| futures-util | 0.3.34 | MIT OR Apache-2.0 |
| gbm | 0.18.0 | MIT |
| gbm-sys | 0.4.0 | MIT |
| generic-array | 0.14.7 | MIT |
| gethostname | 1.1.0 | Apache-2.0 |
| getrandom | 0.2.17 | MIT OR Apache-2.0 |
| getrandom | 0.3.4 | MIT OR Apache-2.0 |
| getrandom | 0.4.3 | MIT OR Apache-2.0 |
| ghash | 0.5.1 | Apache-2.0 OR MIT |
| gl | 0.14.0 | Apache-2.0 |
| gl_generator | 0.14.0 | Apache-2.0 |
| glob | 0.3.4 | MIT OR Apache-2.0 |
| glow | 0.13.1 | MIT OR Apache-2.0 OR Zlib |
| glow | 0.14.2 | MIT OR Apache-2.0 OR Zlib |
| glutin | 0.32.3 | Apache-2.0 |
| glutin-winit | 0.5.0 | MIT |
| glutin_egl_sys | 0.7.1 | Apache-2.0 |
| glutin_glx_sys | 0.6.1 | Apache-2.0 |
| glutin_wgl_sys | 0.6.1 | Apache-2.0 |
| gpu-alloc | 0.6.2 | MIT OR Apache-2.0 |
| gpu-alloc-types | 0.3.1 | MIT OR Apache-2.0 |
| gpu-allocator | 0.26.0 | MIT OR Apache-2.0 |
| gpu-descriptor | 0.3.2 | MIT OR Apache-2.0 |
| gpu-descriptor-types | 0.2.0 | MIT OR Apache-2.0 |
| hashbrown | 0.15.5 | MIT OR Apache-2.0 |
| hashbrown | 0.17.1 | MIT OR Apache-2.0 |
| hassle-rs | 0.11.0 | MIT |
| heck | 0.5.0 | MIT OR Apache-2.0 |
| hermit-abi | 0.5.3 | MIT OR Apache-2.0 |
| hex | 0.4.3 | MIT OR Apache-2.0 |
| hexf-parse | 0.2.1 | CC0-1.0 |
| hmac | 0.12.1 | MIT OR Apache-2.0 |
| home | 0.5.12 | MIT OR Apache-2.0 |
| hybrid-array | 0.4.15 | MIT OR Apache-2.0 |
| icu_collections | 2.4.0 | Unicode-3.0 |
| icu_locale_core | 2.4.0 | Unicode-3.0 |
| icu_normalizer | 2.4.0 | Unicode-3.0 |
| icu_normalizer_data | 2.4.0 | Unicode-3.0 |
| icu_properties | 2.4.0 | Unicode-3.0 |
| icu_properties_data | 2.4.0 | Unicode-3.0 |
| icu_provider | 2.4.0 | Unicode-3.0 |
| idna | 1.1.0 | MIT OR Apache-2.0 |
| idna_adapter | 1.2.2 | Apache-2.0 OR MIT |
| image | 0.25.10 | MIT OR Apache-2.0 |
| image-webp | 0.2.4 | MIT OR Apache-2.0 |
| immutable-chunkmap | 2.1.5 | Apache-2.0 OR MIT |
| indexmap | 2.14.2 | Apache-2.0 OR MIT |
| inout | 0.1.4 | MIT OR Apache-2.0 |
| is_terminal_polyfill | 1.70.2 | MIT OR Apache-2.0 |
| itertools | 0.13.0 | MIT OR Apache-2.0 |
| itoa | 1.0.19 | MIT OR Apache-2.0 |
| jni | 0.21.1 | MIT/Apache-2.0 |
| jni | 0.22.4 | MIT OR Apache-2.0 |
| jni-macros | 0.22.4 | MIT OR Apache-2.0 |
| jni-sys | 0.3.1 | MIT OR Apache-2.0 |
| jni-sys | 0.4.1 | MIT OR Apache-2.0 |
| jni-sys-macros | 0.4.1 | MIT OR Apache-2.0 |
| jobserver | 0.1.35 | MIT OR Apache-2.0 |
| js-sys | 0.3.107 | MIT OR Apache-2.0 |
| keccak | 0.2.2 | Apache-2.0 OR MIT |
| khronos-egl | 6.0.0 | MIT/Apache-2.0 |
| khronos_api | 3.1.0 | Apache-2.0 |
| lazy_static | 1.5.1 | MIT OR Apache-2.0 |
| lazycell | 1.3.0 | MIT/Apache-2.0 |
| libc | 0.2.191 | MIT OR Apache-2.0 |
| libloading | 0.8.9 | ISC |
| libredox | 0.1.25 | MIT |
| libspa | 0.10.1 | MIT |
| libspa-sys | 0.10.1 | MIT |
| libwayshot-xcap | 0.3.3 | BSD-2-Clause |
| linux-raw-sys | 0.12.1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| linux-raw-sys | 0.4.15 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| linux-raw-sys | 0.9.4 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| litemap | 0.8.3 | Unicode-3.0 |
| litrs | 1.0.1 | MIT OR Apache-2.0 |
| lock_api | 0.4.14 | MIT OR Apache-2.0 |
| log | 0.4.35 | MIT OR Apache-2.0 |
| mach2 | 0.4.3 | BSD-2-Clause OR MIT OR Apache-2.0 |
| malloc_buf | 0.0.6 | MIT |
| matrixmultiply | 0.3.11 | MIT/Apache-2.0 |
| memchr | 2.8.3 | Unlicense OR MIT |
| memmap2 | 0.9.12 | MIT OR Apache-2.0 |
| memoffset | 0.9.1 | MIT |
| metal | 0.18.0 | MIT OR Apache-2.0 |
| metal | 0.29.0 | MIT OR Apache-2.0 |
| minimal-lexical | 0.2.1 | MIT/Apache-2.0 |
| miniz_oxide | 0.8.9 | MIT OR Zlib OR Apache-2.0 |
| miniz_oxide | 0.9.1 | MIT OR Zlib OR Apache-2.0 |
| ml-dsa | 0.1.2 | Apache-2.0 OR MIT |
| module-lattice | 0.2.4 | Apache-2.0 OR MIT |
| moxcms | 0.8.1 | BSD-3-Clause OR Apache-2.0 |
| naga | 22.1.0 | MIT OR Apache-2.0 |
| nanorand | 0.7.0 | Zlib |
| nasm-rs | 0.3.2 | MIT OR Apache-2.0 |
| ndarray | 0.16.1 | MIT OR Apache-2.0 |
| ndk | 0.8.0 | MIT OR Apache-2.0 |
| ndk | 0.9.0 | MIT OR Apache-2.0 |
| ndk-context | 0.1.1 | MIT OR Apache-2.0 |
| ndk-sys | 0.5.0+25.2.9519653 | MIT OR Apache-2.0 |
| ndk-sys | 0.6.0+11769913 | MIT OR Apache-2.0 |
| nix | 0.29.0 | MIT |
| nix | 0.31.3 | MIT |
| nohash-hasher | 0.2.0 | Apache-2.0 OR MIT |
| nokhwa | 0.10.11 | Apache-2.0 |
| nokhwa-bindings-linux | 0.1.4 | Apache-2.0 |
| nokhwa-bindings-macos | 0.2.4 | Apache-2.0 |
| nokhwa-bindings-windows | 0.4.6 | Apache-2.0 |
| nokhwa-core | 0.1.9 | Apache-2.0 |
| nom | 7.1.3 | MIT |
| nom | 8.0.0 | MIT |
| num-bigint | 0.4.8 | MIT OR Apache-2.0 |
| num-complex | 0.4.6 | MIT OR Apache-2.0 |
| num-conv | 0.2.2 | MIT OR Apache-2.0 |
| num-derive | 0.4.2 | MIT OR Apache-2.0 |
| num-integer | 0.1.47 | MIT OR Apache-2.0 |
| num-traits | 0.2.19 | MIT OR Apache-2.0 |
| num_enum | 0.7.6 | BSD-3-Clause OR MIT OR Apache-2.0 |
| num_enum_derive | 0.7.6 | BSD-3-Clause OR MIT OR Apache-2.0 |
| objc | 0.2.7 | MIT |
| objc-foundation | 0.1.1 | MIT |
| objc-sys | 0.3.5 | MIT |
| objc2 | 0.5.3 | MIT |
| objc2 | 0.6.5 | MIT |
| objc2-app-kit | 0.2.2 | MIT |
| objc2-app-kit | 0.3.2 | Zlib OR Apache-2.0 OR MIT |
| objc2-av-foundation | 0.3.2 | Zlib OR Apache-2.0 OR MIT |
| objc2-cloud-kit | 0.2.2 | MIT |
| objc2-contacts | 0.2.2 | MIT |
| objc2-core-audio | 0.3.2 | Zlib OR Apache-2.0 OR MIT |
| objc2-core-audio-types | 0.3.2 | Zlib OR Apache-2.0 OR MIT |
| objc2-core-data | 0.2.2 | MIT |
| objc2-core-foundation | 0.3.2 | Zlib OR Apache-2.0 OR MIT |
| objc2-core-graphics | 0.3.2 | Zlib OR Apache-2.0 OR MIT |
| objc2-core-image | 0.2.2 | MIT |
| objc2-core-location | 0.2.2 | MIT |
| objc2-core-media | 0.3.2 | Zlib OR Apache-2.0 OR MIT |
| objc2-core-video | 0.3.2 | Zlib OR Apache-2.0 OR MIT |
| objc2-encode | 4.1.0 | MIT |
| objc2-foundation | 0.2.2 | MIT |
| objc2-foundation | 0.3.2 | MIT |
| objc2-io-surface | 0.3.2 | Zlib OR Apache-2.0 OR MIT |
| objc2-link-presentation | 0.2.2 | MIT |
| objc2-metal | 0.2.2 | MIT |
| objc2-quartz-core | 0.2.2 | MIT |
| objc2-symbols | 0.2.2 | MIT |
| objc2-ui-kit | 0.2.2 | MIT |
| objc2-uniform-type-identifiers | 0.2.2 | MIT |
| objc2-user-notifications | 0.2.2 | MIT |
| objc_exception | 0.1.2 | MIT |
| objc_id | 0.1.1 | MIT |
| oboe | 0.6.1 | Apache-2.0 |
| oboe-sys | 0.6.1 | Apache-2.0 |
| oid-registry | 0.8.1 | MIT OR Apache-2.0 |
| once_cell | 1.21.4 | MIT OR Apache-2.0 |
| once_cell_polyfill | 1.70.2 | MIT OR Apache-2.0 |
| opaque-debug | 0.3.1 | MIT OR Apache-2.0 |
| openh264 | 0.9.9 | BSD-2-Clause |
| openh264-sys2 | 0.9.9 | BSD-2-Clause |
| orbclient | 0.3.55 | MIT |
| ordered-stream | 0.2.0 | MIT OR Apache-2.0 |
| ort | 2.0.0-rc.10 | MIT OR Apache-2.0 |
| ort-sys | 2.0.0-rc.10 | MIT OR Apache-2.0 |
| owned_ttf_parser | 0.25.1 | Apache-2.0 |
| p12-keystore | 0.2.1 | MIT/Apache-2.0 |
| parking | 2.2.1 | Apache-2.0 OR MIT |
| parking_lot | 0.12.5 | MIT OR Apache-2.0 |
| parking_lot_core | 0.9.12 | MIT OR Apache-2.0 |
| password-hash | 0.5.0 | MIT OR Apache-2.0 |
| paste | 1.0.15 | MIT OR Apache-2.0 |
| pbkdf2 | 0.12.2 | MIT OR Apache-2.0 |
| peeking_take_while | 0.1.2 | Apache-2.0/MIT |
| pem | 3.0.6 | MIT |
| pem-rfc7468 | 0.7.0 | Apache-2.0 OR MIT |
| percent-encoding | 2.3.2 | MIT OR Apache-2.0 |
| pin-project | 1.1.13 | Apache-2.0 OR MIT |
| pin-project-internal | 1.1.13 | Apache-2.0 OR MIT |
| pin-project-lite | 0.2.17 | Apache-2.0 OR MIT |
| piper | 0.2.5 | MIT OR Apache-2.0 |
| pipewire | 0.10.1 | MIT |
| pipewire-sys | 0.10.1 | MIT |
| pkcs12 | 0.1.0 | Apache-2.0 OR MIT |
| pkcs5 | 0.7.1 | Apache-2.0 OR MIT |
| pkcs8 | 0.11.0 | Apache-2.0 OR MIT |
| pkg-config | 0.3.34 | MIT OR Apache-2.0 |
| plain | 0.2.3 | MIT/Apache-2.0 |
| png | 0.18.1 | MIT OR Apache-2.0 |
| polling | 3.11.0 | Apache-2.0 OR MIT |
| pollster | 0.3.0 | Apache-2.0/MIT |
| pollster | 1.0.1 | Apache-2.0/MIT |
| polyval | 0.6.2 | Apache-2.0 OR MIT |
| portable-atomic | 1.15.0 | Apache-2.0 OR MIT |
| portable-atomic-util | 0.2.8 | Apache-2.0 OR MIT |
| potential_utf | 0.1.6 | Unicode-3.0 |
| powerfmt | 0.2.1 | MIT OR Apache-2.0 |
| ppv-lite86 | 0.2.21 | MIT OR Apache-2.0 |
| presser | 0.3.1 | MIT OR Apache-2.0 |
| prettyplease | 0.2.37 | MIT OR Apache-2.0 |
| proc-macro-crate | 3.5.0 | MIT OR Apache-2.0 |
| proc-macro2 | 1.0.107 | MIT OR Apache-2.0 |
| profiling | 1.0.18 | MIT OR Apache-2.0 |
| pxfm | 0.1.30 | BSD-3-Clause OR Apache-2.0 |
| quick-error | 2.0.1 | MIT/Apache-2.0 |
| quick-xml | 0.30.0 | MIT |
| quick-xml | 0.41.0 | MIT |
| quote | 1.0.47 | MIT OR Apache-2.0 |
| r-efi | 5.3.0 | MIT OR Apache-2.0 OR LGPL-2.1-or-later |
| r-efi | 6.0.0 | MIT OR Apache-2.0 OR LGPL-2.1-or-later |
| rand | 0.10.3 | MIT OR Apache-2.0 |
| rand | 0.8.8 | MIT OR Apache-2.0 |
| rand_chacha | 0.3.1 | MIT OR Apache-2.0 |
| rand_core | 0.10.1 | MIT OR Apache-2.0 |
| rand_core | 0.6.4 | MIT OR Apache-2.0 |
| raw-window-handle | 0.6.2 | MIT OR Apache-2.0 OR Zlib |
| rawpointer | 0.2.1 | MIT/Apache-2.0 |
| rc2 | 0.8.1 | MIT OR Apache-2.0 |
| rcgen | 0.13.2 | MIT OR Apache-2.0 |
| redox_syscall | 0.4.1 | MIT |
| redox_syscall | 0.5.18 | MIT |
| redox_syscall | 0.9.5 | MIT |
| regex | 1.13.1 | MIT OR Apache-2.0 |
| regex-automata | 0.4.18 | MIT OR Apache-2.0 |
| regex-syntax | 0.8.11 | MIT OR Apache-2.0 |
| renderdoc-sys | 1.1.0 | MIT OR Apache-2.0 |
| rfd | 0.14.1 | MIT |
| ring | 0.17.14 | Apache-2.0 AND ISC |
| rpassword | 7.5.4 | Apache-2.0 |
| rtoolbox | 0.0.6 | Apache-2.0 |
| rustc-hash | 1.1.0 | Apache-2.0/MIT |
| rustc-hash | 2.1.3 | Apache-2.0 OR MIT |
| rustc_version | 0.4.1 | MIT OR Apache-2.0 |
| rusticata-macros | 4.1.0 | MIT/Apache-2.0 |
| rustix | 0.38.44 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| rustix | 1.1.6 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| rustls | 0.23.46 | Apache-2.0 OR ISC OR MIT |
| rustls-pki-types | 1.15.1 | MIT OR Apache-2.0 |
| rustls-webpki | 0.103.16 | ISC |
| rustversion | 1.0.23 | MIT OR Apache-2.0 |
| safe_arch | 1.2.0 | Zlib OR Apache-2.0 OR MIT |
| salsa20 | 0.10.2 | MIT OR Apache-2.0 |
| same-file | 1.0.6 | Unlicense/MIT |
| scoped-tls | 1.0.1 | MIT/Apache-2.0 |
| scopeguard | 1.2.0 | MIT OR Apache-2.0 |
| scrypt | 0.11.0 | MIT OR Apache-2.0 |
| sctk-adwaita | 0.10.1 | MIT |
| semver | 1.0.28 | MIT OR Apache-2.0 |
| serde | 1.0.229 | MIT OR Apache-2.0 |
| serde_core | 1.0.229 | MIT OR Apache-2.0 |
| serde_derive | 1.0.229 | MIT OR Apache-2.0 |
| serde_json | 1.0.154 | MIT OR Apache-2.0 |
| serde_repr | 0.1.21 | MIT OR Apache-2.0 |
| serde_spanned | 1.1.3 | MIT OR Apache-2.0 |
| sha1 | 0.10.7 | MIT OR Apache-2.0 |
| sha2 | 0.10.9 | MIT OR Apache-2.0 |
| shake | 0.1.0 | MIT OR Apache-2.0 |
| shlex | 1.3.0 | MIT OR Apache-2.0 |
| shlex | 2.0.1 | MIT OR Apache-2.0 |
| signal-hook-registry | 1.4.8 | MIT OR Apache-2.0 |
| signature | 3.0.0 | Apache-2.0 OR MIT |
| simd-adler32 | 0.3.10 | MIT |
| simd_cesu8 | 1.2.0 | Apache-2.0 OR MIT |
| simdutf8 | 0.1.5 | MIT OR Apache-2.0 |
| slab | 0.4.12 | MIT |
| slotmap | 1.1.1 | Zlib |
| smallvec | 1.16.5 | MIT OR Apache-2.0 |
| smallvec | 2.0.0-alpha.10 | MIT OR Apache-2.0 |
| smithay-client-toolkit | 0.19.2 | MIT |
| smithay-client-toolkit | 0.20.0 | MIT |
| smithay-clipboard | 0.7.3 | MIT |
| smol_str | 0.2.2 | MIT OR Apache-2.0 |
| socket2 | 0.6.5 | MIT OR Apache-2.0 |
| spin | 0.9.9 | MIT |
| spirv | 0.3.0+sdk-1.3.268.0 | Apache-2.0 |
| spki | 0.7.3 | Apache-2.0 OR MIT |
| spki | 0.8.1 | Apache-2.0 OR MIT |
| sponge-cursor | 0.1.0 | MIT OR Apache-2.0 |
| stable_deref_trait | 1.2.1 | MIT OR Apache-2.0 |
| static_assertions | 1.1.0 | MIT OR Apache-2.0 |
| strict-num | 0.1.1 | MIT |
| strsim | 0.11.1 | MIT |
| subtle | 2.6.1 | BSD-3-Clause |
| syn | 1.0.109 | MIT OR Apache-2.0 |
| syn | 2.0.119 | MIT OR Apache-2.0 |
| syn | 3.0.9 | MIT OR Apache-2.0 |
| synstructure | 0.13.2 | MIT |
| synstructure | 0.14.0 | MIT |
| system-deps | 7.0.8 | MIT OR Apache-2.0 |
| target-lexicon | 0.13.5 | Apache-2.0 WITH LLVM-exception |
| tempfile | 3.27.0 | MIT OR Apache-2.0 |
| termcolor | 1.4.1 | Unlicense OR MIT |
| thiserror | 1.0.69 | MIT OR Apache-2.0 |
| thiserror | 2.0.21 | MIT OR Apache-2.0 |
| thiserror-impl | 1.0.69 | MIT OR Apache-2.0 |
| thiserror-impl | 2.0.21 | MIT OR Apache-2.0 |
| time | 0.3.55 | MIT OR Apache-2.0 |
| time-core | 0.1.9 | MIT OR Apache-2.0 |
| time-macros | 0.2.32 | MIT OR Apache-2.0 |
| tiny-skia | 0.11.4 | BSD-3-Clause |
| tiny-skia-path | 0.11.4 | BSD-3-Clause |
| tinystr | 0.8.5 | Unicode-3.0 |
| tokio | 1.54.1 | MIT |
| toml | 1.2.1+spec-1.1.0 | MIT OR Apache-2.0 |
| toml_datetime | 1.1.3+spec-1.1.0 | MIT OR Apache-2.0 |
| toml_edit | 0.25.19+spec-1.1.0 | MIT OR Apache-2.0 |
| toml_parser | 1.1.5+spec-1.1.0 | MIT OR Apache-2.0 |
| toml_writer | 1.2.0+spec-1.1.0 | MIT OR Apache-2.0 |
| tracing | 0.1.44 | MIT |
| tracing-attributes | 0.1.31 | MIT |
| tracing-core | 0.1.36 | MIT |
| ttf-parser | 0.25.1 | MIT OR Apache-2.0 |
| type-map | 0.5.1 | MIT/Apache-2.0 |
| typenum | 1.20.1 | MIT OR Apache-2.0 |
| uds_windows | 1.2.1 | MIT |
| unicode-ident | 1.0.27 | (MIT OR Apache-2.0) AND Unicode-3.0 |
| unicode-segmentation | 1.13.3 | MIT OR Apache-2.0 |
| unicode-width | 0.1.14 | MIT OR Apache-2.0 |
| unicode-width | 0.2.3 | MIT OR Apache-2.0 |
| unicode-xid | 0.2.6 | MIT OR Apache-2.0 |
| universal-hash | 0.5.1 | MIT OR Apache-2.0 |
| untrusted | 0.9.0 | ISC |
| url | 2.5.8 | MIT OR Apache-2.0 |
| urlencoding | 2.1.3 | MIT |
| utf8_iter | 1.1.0 | Apache-2.0 OR MIT |
| utf8parse | 0.2.2 | Apache-2.0 OR MIT |
| utf_types | 1.0.0 | Apache-2.0 OR MIT |
| uuid | 1.29.2 | Apache-2.0 OR MIT |
| v4l | 0.14.0 | MIT |
| v4l2-sys-mit | 0.3.0 | MIT |
| version-compare | 0.2.1 | MIT |
| version_check | 0.9.5 | MIT/Apache-2.0 |
| walkdir | 2.5.0 | Unlicense/MIT |
| wasi | 0.11.1+wasi-snapshot-preview1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| wasip2 | 1.0.4+wasi-0.2.12 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| wasm-bindgen | 0.2.130 | MIT OR Apache-2.0 |
| wasm-bindgen-futures | 0.4.80 | MIT OR Apache-2.0 |
| wasm-bindgen-macro | 0.2.130 | MIT OR Apache-2.0 |
| wasm-bindgen-macro-support | 0.2.130 | MIT OR Apache-2.0 |
| wasm-bindgen-shared | 0.2.130 | MIT OR Apache-2.0 |
| wayland-backend | 0.3.17 | MIT |
| wayland-client | 0.31.15 | MIT |
| wayland-csd-frame | 0.3.0 | MIT |
| wayland-cursor | 0.31.14 | MIT |
| wayland-protocols | 0.32.13 | MIT |
| wayland-protocols-experimental | 20250721.0.1 | MIT |
| wayland-protocols-misc | 0.3.12 | MIT |
| wayland-protocols-plasma | 0.3.12 | MIT |
| wayland-protocols-wlr | 0.3.12 | MIT |
| wayland-scanner | 0.31.11 | MIT |
| wayland-server | 0.31.14 | MIT |
| wayland-sys | 0.31.11 | MIT |
| web-sys | 0.3.107 | MIT OR Apache-2.0 |
| web-time | 1.1.0 | MIT OR Apache-2.0 |
| webbrowser | 1.2.6 | MIT OR Apache-2.0 |
| wgpu | 22.1.0 | MIT OR Apache-2.0 |
| wgpu-core | 22.1.0 | MIT OR Apache-2.0 |
| wgpu-hal | 22.0.0 | MIT OR Apache-2.0 |
| wgpu-types | 22.0.0 | MIT OR Apache-2.0 |
| which | 4.4.2 | MIT |
| wide | 1.7.1 | Zlib OR Apache-2.0 OR MIT |
| widestring | 1.2.1 | MIT OR Apache-2.0 |
| winapi | 0.3.9 | MIT/Apache-2.0 |
| winapi-i686-pc-windows-gnu | 0.4.0 | MIT/Apache-2.0 |
| winapi-util | 0.1.11 | Unlicense OR MIT |
| winapi-x86_64-pc-windows-gnu | 0.4.0 | MIT/Apache-2.0 |
| windows | 0.52.0 | MIT OR Apache-2.0 |
| windows | 0.54.0 | MIT OR Apache-2.0 |
| windows | 0.58.0 | MIT OR Apache-2.0 |
| windows | 0.62.2 | MIT OR Apache-2.0 |
| windows-collections | 0.3.2 | MIT OR Apache-2.0 |
| windows-core | 0.52.0 | MIT OR Apache-2.0 |
| windows-core | 0.54.0 | MIT OR Apache-2.0 |
| windows-core | 0.58.0 | MIT OR Apache-2.0 |
| windows-core | 0.62.2 | MIT OR Apache-2.0 |
| windows-future | 0.3.2 | MIT OR Apache-2.0 |
| windows-implement | 0.58.0 | MIT OR Apache-2.0 |
| windows-implement | 0.60.2 | MIT OR Apache-2.0 |
| windows-interface | 0.58.0 | MIT OR Apache-2.0 |
| windows-interface | 0.59.3 | MIT OR Apache-2.0 |
| windows-link | 0.2.1 | MIT OR Apache-2.0 |
| windows-numerics | 0.3.1 | MIT OR Apache-2.0 |
| windows-result | 0.1.2 | MIT OR Apache-2.0 |
| windows-result | 0.2.0 | MIT OR Apache-2.0 |
| windows-result | 0.4.1 | MIT OR Apache-2.0 |
| windows-strings | 0.1.0 | MIT OR Apache-2.0 |
| windows-strings | 0.5.1 | MIT OR Apache-2.0 |
| windows-sys | 0.45.0 | MIT OR Apache-2.0 |
| windows-sys | 0.48.0 | MIT OR Apache-2.0 |
| windows-sys | 0.52.0 | MIT OR Apache-2.0 |
| windows-sys | 0.59.0 | MIT OR Apache-2.0 |
| windows-sys | 0.61.2 | MIT OR Apache-2.0 |
| windows-targets | 0.42.2 | MIT OR Apache-2.0 |
| windows-targets | 0.48.5 | MIT OR Apache-2.0 |
| windows-targets | 0.52.6 | MIT OR Apache-2.0 |
| windows-threading | 0.2.1 | MIT OR Apache-2.0 |
| windows_aarch64_gnullvm | 0.42.2 | MIT OR Apache-2.0 |
| windows_aarch64_gnullvm | 0.48.5 | MIT OR Apache-2.0 |
| windows_aarch64_gnullvm | 0.52.6 | MIT OR Apache-2.0 |
| windows_aarch64_msvc | 0.42.2 | MIT OR Apache-2.0 |
| windows_aarch64_msvc | 0.48.5 | MIT OR Apache-2.0 |
| windows_aarch64_msvc | 0.52.6 | MIT OR Apache-2.0 |
| windows_i686_gnu | 0.42.2 | MIT OR Apache-2.0 |
| windows_i686_gnu | 0.48.5 | MIT OR Apache-2.0 |
| windows_i686_gnu | 0.52.6 | MIT OR Apache-2.0 |
| windows_i686_gnullvm | 0.52.6 | MIT OR Apache-2.0 |
| windows_i686_msvc | 0.42.2 | MIT OR Apache-2.0 |
| windows_i686_msvc | 0.48.5 | MIT OR Apache-2.0 |
| windows_i686_msvc | 0.52.6 | MIT OR Apache-2.0 |
| windows_x86_64_gnu | 0.42.2 | MIT OR Apache-2.0 |
| windows_x86_64_gnu | 0.48.5 | MIT OR Apache-2.0 |
| windows_x86_64_gnu | 0.52.6 | MIT OR Apache-2.0 |
| windows_x86_64_gnullvm | 0.42.2 | MIT OR Apache-2.0 |
| windows_x86_64_gnullvm | 0.48.5 | MIT OR Apache-2.0 |
| windows_x86_64_gnullvm | 0.52.6 | MIT OR Apache-2.0 |
| windows_x86_64_msvc | 0.42.2 | MIT OR Apache-2.0 |
| windows_x86_64_msvc | 0.48.5 | MIT OR Apache-2.0 |
| windows_x86_64_msvc | 0.52.6 | MIT OR Apache-2.0 |
| winit | 0.30.13 | Apache-2.0 |
| winnow | 1.0.5 | MIT |
| wit-bindgen | 0.57.1 | Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT |
| writeable | 0.6.4 | Unicode-3.0 |
| x11-dl | 2.21.0 | MIT |
| x11rb | 0.13.2 | MIT OR Apache-2.0 |
| x11rb-protocol | 0.13.2 | MIT OR Apache-2.0 |
| x509-cert | 0.2.5 | Apache-2.0 OR MIT |
| x509-parser | 0.18.1 | MIT OR Apache-2.0 |
| xcap | 0.9.10 | Apache-2.0 |
| xcb | 1.7.1 | MIT |
| xcursor | 0.3.11 | MIT |
| xdg-home | 1.3.0 | MIT |
| xkbcommon-dl | 0.4.2 | MIT |
| xkeysym | 0.2.1 | MIT OR Apache-2.0 OR Zlib |
| xml-rs | 0.8.29 | MIT |
| yasna | 0.5.2 | MIT OR Apache-2.0 |
| yoke | 0.8.4 | Unicode-3.0 |
| yoke-derive | 0.8.4 | Unicode-3.0 |
| zbus | 4.4.0 | MIT |
| zbus | 6.2.1 | MIT |
| zbus-lockstep | 0.4.4 | MIT |
| zbus-lockstep-macros | 0.4.4 | MIT |
| zbus_macros | 4.4.0 | MIT |
| zbus_macros | 6.2.1 | MIT |
| zbus_names | 3.0.0 | MIT |
| zbus_utils | 6.1.0 | MIT |
| zbus_xml | 4.0.0 | MIT |
| zcheapstr | 1.2.0 | MIT |
| zerocopy | 0.8.63 | BSD-2-Clause OR Apache-2.0 OR MIT |
| zerocopy-derive | 0.8.63 | BSD-2-Clause OR Apache-2.0 OR MIT |
| zerofrom | 0.1.8 | Unicode-3.0 |
| zerofrom-derive | 0.1.8 | Unicode-3.0 |
| zeroize | 1.9.1 | Apache-2.0 OR MIT |
| zeroize_derive | 1.5.0 | Apache-2.0 OR MIT |
| zerotrie | 0.2.5 | Unicode-3.0 |
| zerovec | 0.11.9 | Unicode-3.0 |
| zerovec-derive | 0.11.7 | Unicode-3.0 |
| zlib-rs | 0.6.8 | Zlib |
| zmij | 1.0.23 | MIT |
| zruntime | 0.2.2 | MIT |
| zune-core | 0.5.3 | MIT OR Apache-2.0 OR Zlib |
| zune-jpeg | 0.5.15 | MIT OR Apache-2.0 OR Zlib |
| zvariant | 4.2.0 | MIT |
| zvariant_derive | 4.2.0 | MIT |
| zvariant_utils | 2.1.0 | MIT |
