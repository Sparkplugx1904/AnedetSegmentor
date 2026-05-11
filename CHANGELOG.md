# Changelog

All notable changes to the Conjunctiva Segmentation project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-05-11

### Added
- Initial release of Conjunctiva Segmentation Android app
- Real-time camera feed with CameraX
- TensorFlow Lite FP16 model integration
- YOLOv8 segmentation model inference
- Custom OverlayView for polygon rendering
- Performance metrics display (FPS, inference time, detections)
- XNNPACK acceleration for CPU
- GPU delegate support (optional)
- Image preprocessing and postprocessing utilities
- Non-Maximum Suppression (NMS) for duplicate removal
- Transparent overlay rendering
- Camera permission handling
- Portrait orientation lock
- ProGuard rules for release builds

### Features
- **Real-time Segmentation**: 15-30 FPS on mid-range to flagship devices
- **Visual Feedback**: Semi-transparent polygon overlay with bounding boxes
- **Performance Monitoring**: Live FPS and inference time display
- **Optimized Inference**: FP16 model with XNNPACK and GPU acceleration
- **Offline Operation**: No internet required, all processing on-device
- **Privacy-First**: No data collection or external communication

### Technical Details
- Min SDK: 24 (Android 7.0)
- Target SDK: 34 (Android 14)
- Model: YOLOv8n-seg FP16 (320x320 input)
- Inference Time: 20-80ms (device-dependent)
- Memory Usage: ~150MB
- APK Size: ~15MB (debug), ~10MB (release)

### Documentation
- README.md: Project overview and features
- QUICKSTART.md: Quick setup guide
- IMPLEMENTATION_GUIDE.md: Technical implementation details
- BUILD_INSTRUCTIONS.md: Complete build guide
- TEST_PLAN.md: Testing strategy and test cases
- PROJECT_SUMMARY.md: High-level project summary

### Testing
- Unit tests for ImageUtils
- Instrumented tests for model inference
- Manual testing on multiple devices
- Performance benchmarking

### Known Issues
- GPU delegate may not work on all devices (automatic fallback to CPU)
- Inference time varies significantly across device tiers
- Polygon rendering is simplified (uses bounding box corners)

### Future Enhancements
- Detailed mask extraction from Proto output
- Screenshot and save functionality
- Detection history
- Multi-class support
- Settings screen
- Dark/Light theme toggle

---

## [Unreleased]

### Planned for v1.1.0
- [ ] Screenshot capture feature
- [ ] Save results to gallery
- [ ] Detection history view
- [ ] Settings screen (threshold, colors, frame skip)
- [ ] Multi-language support (English, Indonesian)

### Planned for v1.2.0
- [ ] Multiple class detection (normal, abnormal, etc.)
- [ ] Detailed masking from Proto output
- [ ] Export results to CSV/JSON
- [ ] Integration with medical records

### Planned for v2.0.0
- [ ] Model quantization to INT8
- [ ] Batch processing mode
- [ ] Cloud sync (optional)
- [ ] Advanced analytics

---

## Version History

| Version | Release Date | Highlights |
|---------|--------------|------------|
| 1.0.0 | 2026-05-11 | Initial release with real-time segmentation |

---

## Migration Guide

### From Development to v1.0.0
No migration needed - this is the first release.

---

## Contributors

- Initial development and implementation
- Model training and optimization
- Documentation and testing

---

## Support

For issues, questions, or contributions:
1. Check documentation files
2. Review test plans
3. Check logcat for errors
4. Open GitHub issue (if applicable)

---

**Note**: This changelog will be updated with each release. Please check back for the latest changes and improvements.
