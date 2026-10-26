# Lyrics in the Sky: what the default speakers play (WASAPI loopback), for "Hoergenau".
# Started by the mod only while it listens to a song; writes one header line, then 16 kHz mono 16-bit PCM on stdout.
# Stops when the mod closes stdout or when Minecraft (parent PID) is gone. Nothing is written to disk.
param([int]$ParentPid = 0)
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

public static class LyricsLoopback {
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorComObject {}

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams,
            [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient {
        [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity,
            IntPtr format, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition,
            out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    const int OUT_RATE = 16000;

    static void Check(int hr, string what) {
        if (hr < 0) throw new Exception(what + " failed: 0x" + hr.ToString("X8"));
    }

    public static void Run(int parentPid) {
        var enumerator = (IMMDeviceEnumerator) new MMDeviceEnumeratorComObject();
        IMMDevice device;
        Check(enumerator.GetDefaultAudioEndpoint(0 /* render */, 1 /* multimedia */, out device), "default device");
        Guid clientId = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
        object clientObject;
        Check(device.Activate(ref clientId, 23 /* CLSCTX_ALL */, IntPtr.Zero, out clientObject), "activate");
        var client = (IAudioClient) clientObject;
        IntPtr format;
        Check(client.GetMixFormat(out format), "mix format");
        int channels = Marshal.ReadInt16(format, 2);
        int rate = Marshal.ReadInt32(format, 4);
        int bits = Marshal.ReadInt16(format, 14);
        int tag = Marshal.ReadInt16(format, 0) & 0xFFFF;
        // WAVE_FORMAT_EXTENSIBLE carries the real format in its sub-format GUID; float is the usual mix format.
        bool isFloat = tag == 3 || (tag == 0xFFFE && Marshal.ReadInt32(format, 24) == 3);
        Check(client.Initialize(0 /* shared */, 0x00020000 /* loopback */, 10000000, 0, format, IntPtr.Zero), "initialize");
        Guid captureId = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
        object captureObject;
        Check(client.GetService(ref captureId, out captureObject), "capture service");
        var capture = (IAudioCaptureClient) captureObject;

        Stream output = Console.OpenStandardOutput();
        byte[] header = System.Text.Encoding.ASCII.GetBytes("LOOPBACK " + OUT_RATE + " " + rate + " " + channels + " " + bits
            + (isFloat ? " float" : " int") + "\n");
        output.Write(header, 0, header.Length);
        output.Flush();

        Process parent = parentPid > 0 ? Process.GetProcessById(parentPid) : null;
        double step = (double) rate / OUT_RATE;  // source frames per output sample
        double sourcePos = 0;                    // position of the next output sample, in source frames of 'carry'
        float carry = 0;                         // last source sample of the previous packet
        var clock = Stopwatch.StartNew();
        long written = 0;
        byte[] buffer = new byte[0];
        Check(client.Start(), "start");
        try {
            while (parent == null || !parent.HasExited) {
                uint packet;
                Check(capture.GetNextPacketSize(out packet), "packet size");
                int count = 0;
                while (packet > 0) {
                    IntPtr data; uint frames, flags; ulong devicePosition, qpc;
                    Check(capture.GetBuffer(out data, out frames, out flags, out devicePosition, out qpc), "buffer");
                    bool silent = (flags & 2) != 0;
                    float[] mono = new float[frames + 1];
                    mono[0] = carry;
                    for (int f = 0; f < frames; f++) {
                        float sum = 0;
                        if (!silent) {
                            for (int c = 0; c < channels; c++) {
                                int offset = (f * channels + c) * (bits / 8);
                                sum += isFloat ? BitConverter.ToSingle(BitConverter.GetBytes(Marshal.ReadInt32(data, offset)), 0)
                                    : bits == 16 ? Marshal.ReadInt16(data, offset) / 32768f
                                    : Marshal.ReadInt32(data, offset) / 2147483648f;
                            }
                        }
                        mono[f + 1] = sum / channels;
                    }
                    Check(capture.ReleaseBuffer(frames), "release");
                    // Linear interpolation from the device rate to 16 kHz; the device mixes at 44.1 or 48 kHz, so
                    // speech and singing below 8 kHz survive well enough for recognition.
                    int max = (int) (frames / step) + 2;
                    if (buffer.Length < max * 2) buffer = new byte[max * 2];
                    while (sourcePos < frames) {
                        int i = (int) sourcePos;
                        double frac = sourcePos - i;
                        float value = (float) (mono[i] * (1 - frac) + mono[i + 1] * frac);
                        short s = (short) Math.Max(-32768, Math.Min(32767, (int) (value * 32767)));
                        buffer[count * 2] = (byte) s;
                        buffer[count * 2 + 1] = (byte) (s >> 8);
                        count++;
                        sourcePos += step;
                    }
                    sourcePos -= frames;
                    carry = mono[frames];
                    output.Write(buffer, 0, count * 2);
                    written += count;
                    count = 0;
                    Check(capture.GetNextPacketSize(out packet), "packet size");
                }
                // Windows sends nothing while nothing plays; silence keeps the time line straight.
                long due = (long) (clock.Elapsed.TotalSeconds * OUT_RATE) - OUT_RATE / 10;
                if (due > written) {
                    int gap = (int) (due - written);
                    output.Write(new byte[gap * 2], 0, gap * 2);
                    written += gap;
                }
                output.Flush();
                Thread.Sleep(20);
            }
        } catch (IOException) {
            // The mod closed the pipe: it stopped listening.
        } finally {
            client.Stop();
        }
    }
}
'@

[LyricsLoopback]::Run($ParentPid)
