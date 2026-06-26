IceCam

«Experimental Android Camera Framework for Virtual Video Sources»

Overview

IceCam is an open-source research project focused on building a flexible virtual video source framework for Android.

The project explores modern Android camera APIs, media pipelines, and framework integration to provide an alternative video source for applications that rely on the standard Android camera stack.

IceCam is designed for developers, researchers, and enthusiasts interested in Android multimedia, Camera2, MediaCodec, native rendering, and low-level system integration.

Why IceCam?

Many Android devices eventually develop hardware camera issues, while others are used in laboratory, streaming, testing, or development environments where a physical camera is not required.

IceCam aims to provide a configurable virtual video pipeline that can be used for scenarios such as:

- replacing a damaged or unavailable camera during development or testing;
- streaming prerecorded media into Android applications for demonstrations;
- creating repeatable test environments for Camera2 applications;
- experimenting with Android multimedia and rendering pipelines;
- debugging applications that depend on camera input;
- content creation, presentations and live streaming workflows;
- educational demonstrations and creative video effects;
- entertainment projects and harmless video-call pranks performed with the knowledge of participants.

IceCam is intended for legitimate development, research, educational, accessibility and creative use cases.

Project Goals

- Universal Camera2-oriented architecture
- Android 12–15 compatibility
- Native rendering pipeline
- Root module integration
- LSPosed framework integration
- MediaCodec-based video pipeline
- Modular architecture
- Automatic diagnostics
- Clean and maintainable codebase

Architecture

Application
        │
        ▼
Provider Bridge
        │
        ▼
Root Service (icecamd)
        │
        ▼
Framework Integration
        │
        ▼
Camera2 Pipeline
        │
        ▼
Native Renderer
        │
        ▼
Media Source

Components

Android Application

- Simple setup interface
- Media selection
- Diagnostic tools
- Configuration management
- Status monitoring

Root Module

- Service management
- Configuration storage
- Logging
- Native daemon launcher
- Automatic diagnostics

Framework Layer

- Camera framework integration
- Camera2 research
- Surface management
- Session monitoring
- Compatibility layer

Native Backend

- MediaCodec pipeline
- Native rendering
- Surface processing
- Performance optimizations

Features

- Media file playback
- Image rendering
- Video rendering
- Automatic diagnostics
- Detailed logging
- Modular configuration
- GitHub Actions build support
- Android 12–17 support

Roadmap

Current development focuses on:

- Framework integration
- Native rendering backend
- Camera2 compatibility
- Performance improvements
- Automated testing
- Improved diagnostics
- Modular plugin architecture

Contributing

Contributions, bug reports, feature requests and technical discussions are welcome.

The project values clean architecture, reproducible testing and detailed diagnostics.

Disclaimer

IceCam is an experimental research project. Compatibility may vary depending on the Android version, device vendor implementation, kernel configuration and camera framework.

The software is intended for development, testing, research, accessibility, multimedia experimentation and other lawful purposes. Users are responsible for ensuring that their use complies with applicable laws, platform policies and the expectations of the people involved.

License

MIT License