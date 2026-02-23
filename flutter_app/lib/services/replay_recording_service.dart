import 'dart:async';
import 'dart:io';

import 'package:camera/camera.dart';
import 'package:ffmpeg_kit_flutter_new_min_gpl/ffmpeg_kit.dart';
import 'package:ffmpeg_kit_flutter_new_min_gpl/return_code.dart';
import 'package:gal/gal.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

class ReplayRecordingService {
  ReplayRecordingService({
    required this.controller,
    this.segmentSeconds = 5,
    this.replayWindowSeconds = 20,
  });

  final CameraController controller;
  final int segmentSeconds;
  final int replayWindowSeconds;

  final List<File> _segments = <File>[];
  final List<File> _sessionSegments = <File>[];
  Timer? _segmentTimer;

  bool _isRecording = false;
  bool _isStopping = false;

  bool get isRecording => _isRecording;

  Future<Directory> _workDir() async {
    final dir = await getApplicationDocumentsDirectory();
    final replayDir = Directory(p.join(dir.path, 'replay_segments'));
    if (!replayDir.existsSync()) {
      replayDir.createSync(recursive: true);
    }
    return replayDir;
  }

  Future<void> startContinuousRecording() async {
    if (_isRecording || _isStopping) return;
    _isRecording = true;
    await _startNewSegment();
    _segmentTimer = Timer.periodic(Duration(seconds: segmentSeconds), (_) async {
      await _rotateSegment();
    });
  }

  Future<void> _startNewSegment() async {
    if (!controller.value.isInitialized || controller.value.isRecordingVideo) {
      return;
    }
    await controller.startVideoRecording();
  }

  Future<void> _rotateSegment() async {
    if (!_isRecording || _isStopping) return;
    if (!controller.value.isRecordingVideo) return;

    final xfile = await controller.stopVideoRecording();
    final dir = await _workDir();
    final ts = DateTime.now().millisecondsSinceEpoch;
    final target = File(p.join(dir.path, 'segment_$ts.mp4'));
    await File(xfile.path).copy(target.path);

    _segments.add(target);
    _sessionSegments.add(target);

    final maxSegments = (replayWindowSeconds / segmentSeconds).ceil() + 1;
    while (_segments.length > maxSegments) {
      _segments.removeAt(0);
    }

    if (_isRecording) {
      await _startNewSegment();
    }
  }

  Future<File?> saveReplay() async {
    if (_segments.isEmpty) return null;

    if (_isRecording && controller.value.isRecordingVideo) {
      await _rotateSegment();
    }

    final selected = List<File>.from(_segments);
    if (selected.isEmpty) return null;

    final merged = await _mergeSegments(selected, prefix: 'replay');
    if (merged == null) return null;

    await Gal.putVideo(merged.path);
    return merged;
  }

  Future<File?> stopAndSaveFullSession() async {
    if (!_isRecording) return null;

    _isStopping = true;
    _segmentTimer?.cancel();

    if (controller.value.isRecordingVideo) {
      await _rotateSegment();
    }

    _isRecording = false;
    _isStopping = false;

    if (_sessionSegments.isEmpty) return null;

    final full = await _mergeSegments(List<File>.from(_sessionSegments), prefix: 'recording');
    if (full == null) return null;

    await Gal.putVideo(full.path);
    return full;
  }

  Future<File?> _mergeSegments(List<File> files, {required String prefix}) async {
    final dir = await _workDir();
    final ts = DateTime.now().millisecondsSinceEpoch;
    final listFile = File(p.join(dir.path, '${prefix}_$ts.txt'));
    final output = File(p.join(dir.path, '${prefix}_$ts.mp4'));

    final content = files.map((f) => "file '${f.path.replaceAll("'", "'\\''")}'").join('\n');
    await listFile.writeAsString(content);

    final cmd = "-f concat -safe 0 -i '${listFile.path}' -c copy '${output.path}'";
    final session = await FFmpegKit.execute(cmd);
    final code = await session.getReturnCode();

    if (code == null || !ReturnCode.isSuccess(code)) {
      return null;
    }
    return output;
  }

  Future<void> dispose() async {
    _segmentTimer?.cancel();
    if (controller.value.isRecordingVideo) {
      await controller.stopVideoRecording();
    }
  }
}
