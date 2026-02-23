import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import 'services/replay_recording_service.dart';

class ReplayCameraPage extends StatefulWidget {
  const ReplayCameraPage({super.key});

  @override
  State<ReplayCameraPage> createState() => _ReplayCameraPageState();
}

class _ReplayCameraPageState extends State<ReplayCameraPage> {
  CameraController? _controller;
  ReplayRecordingService? _recordingService;

  bool _loading = true;
  bool _isRecording = false;
  int _selectedMode = 2;
  String _status = 'Inicializando câmera...';

  static const _modes = ['VLOG', 'VÍDEO', 'REPLAY'];

  @override
  void initState() {
    super.initState();
    _setup();
  }

  Future<void> _setup() async {
    final cameraOk = await Permission.camera.request().isGranted;
    final micOk = await Permission.microphone.request().isGranted;

    if (!cameraOk || !micOk) {
      setState(() {
        _loading = false;
        _status = 'Permissões de câmera e microfone são obrigatórias.';
      });
      return;
    }

    final cameras = await availableCameras();
    final back = cameras.where((c) => c.lensDirection == CameraLensDirection.back).toList();
    final selected = back.isNotEmpty ? back.first : cameras.first;

    final controller = CameraController(
      selected,
      ResolutionPreset.high,
      enableAudio: true,
    );

    await controller.initialize();

    _recordingService = ReplayRecordingService(controller: controller);

    setState(() {
      _controller = controller;
      _loading = false;
      _status = 'Pronto para gravar';
    });
  }

  Future<void> _startRecording() async {
    final service = _recordingService;
    if (service == null) return;

    await service.startContinuousRecording();
    setState(() {
      _isRecording = true;
      _status = 'Gravação contínua iniciada (buffer 20s).';
    });
  }

  Future<void> _stopRecording() async {
    final service = _recordingService;
    if (service == null) return;

    final file = await service.stopAndSaveFullSession();

    setState(() {
      _isRecording = false;
      _status = file == null
          ? 'Gravação parada, sem segmentos para salvar.'
          : 'Sessão completa salva na galeria.';
    });
  }

  Future<void> _saveReplay() async {
    final service = _recordingService;
    if (service == null) return;

    final replay = await service.saveReplay();
    setState(() {
      _status = replay == null
          ? 'Não há replay disponível ainda.'
          : 'Replay dos últimos 20s salvo na galeria.';
    });
  }

  @override
  void dispose() {
    _recordingService?.dispose();
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final label = _selectedMode == 2
        ? (_isRecording ? 'GRAVANDO BUFFER' : 'PRONTO PARA REPLAY')
        : (_isRecording ? 'GRAVANDO' : 'PRONTO');

    return Scaffold(
      backgroundColor: const Color(0xFF090B1F),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : Stack(
                children: [
                  if (_controller != null && _controller!.value.isInitialized)
                    CameraPreview(_controller!)
                  else
                    Container(color: const Color(0xFF14172B)),
                  Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: [Colors.transparent, Color(0xAA090B1F)],
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                          decoration: BoxDecoration(
                            color: Colors.black.withOpacity(0.35),
                            borderRadius: BorderRadius.circular(18),
                            border: Border.all(color: Colors.white24),
                          ),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              const Row(
                                children: [
                                  Icon(Icons.flash_on, size: 16),
                                  SizedBox(width: 8),
                                  Icon(Icons.speed, size: 16),
                                  SizedBox(width: 8),
                                  Icon(Icons.center_focus_strong, size: 16),
                                ],
                              ),
                              Row(
                                children: [
                                  Container(
                                    width: 8,
                                    height: 8,
                                    decoration: BoxDecoration(
                                      color: _isRecording ? Colors.redAccent : Colors.greenAccent,
                                      shape: BoxShape.circle,
                                    ),
                                  ),
                                  const SizedBox(width: 8),
                                  Text(
                                    label,
                                    style: const TextStyle(fontWeight: FontWeight.w700),
                                  ),
                                ],
                              )
                            ],
                          ),
                        ),
                        Column(
                          children: [
                            Text(
                              _status,
                              textAlign: TextAlign.center,
                              style: const TextStyle(fontSize: 13),
                            ),
                            const SizedBox(height: 10),
                            Wrap(
                              alignment: WrapAlignment.center,
                              spacing: 6,
                              children: List.generate(_modes.length, (index) {
                                return ChoiceChip(
                                  label: Text(_modes[index]),
                                  selected: _selectedMode == index,
                                  onSelected: (_) => setState(() => _selectedMode = index),
                                  selectedColor: const Color(0xFFC84DFF),
                                );
                              }),
                            ),
                            const SizedBox(height: 16),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                ElevatedButton.icon(
                                  onPressed: _isRecording ? null : _startRecording,
                                  icon: const Icon(Icons.fiber_manual_record),
                                  label: const Text('Gravar'),
                                ),
                                const SizedBox(width: 8),
                                ElevatedButton.icon(
                                  onPressed: _isRecording ? _stopRecording : null,
                                  icon: const Icon(Icons.stop),
                                  label: const Text('Parar'),
                                ),
                                const SizedBox(width: 8),
                                OutlinedButton.icon(
                                  onPressed: _saveReplay,
                                  icon: const Icon(Icons.replay),
                                  label: const Text('Salvar replay'),
                                ),
                              ],
                            ),
                            const SizedBox(height: 20),
                          ],
                        )
                      ],
                    ),
                  )
                ],
              ),
      ),
    );
  }
}
