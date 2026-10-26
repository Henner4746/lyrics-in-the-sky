# Lyrics in the Sky: what the music player plays, for "Hoergenau".
# Started by the mod only while it listens to a song; writes one header line, then 16 kHz mono 16-bit PCM on stdout.
# Records only the player's own sound (process loopback, Windows 10 2004 and newer) when it can find the player's
# process, so voice chat, videos and the game's own sounds stay out; else, or when the player's own sound stays silent
# while the PC plays something, everything the default speakers play (WASAPI loopback).
# Stops when the mod closes stdout or when Minecraft (parent PID) is gone. Nothing is written to disk.
# Argument 1: PID of Minecraft. Argument 2, if any: the player as the media session names it (its app user model ID).
param([int]$ParentPid = 0, [string]$App = '-')
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
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

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IActivateAudioInterfaceAsyncOperation {
        [PreserveSig] int GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IActivateAudioInterfaceCompletionHandler {
        [PreserveSig] int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
    }

    /** Marks the completion handler as callable from any thread, which process loopback activation requires. */
    [ComImport, Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAgileObject {}

    [ComVisible(true)]
    public class Activation : IActivateAudioInterfaceCompletionHandler, IAgileObject {
        public readonly ManualResetEvent Done = new ManualResetEvent(false);
        public int Result = -1;
        public object Client;

        public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation) {
            int hr;
            object client;
            int call = operation.GetActivateResult(out hr, out client);
            Result = call < 0 ? call : hr;
            Client = client;
            Done.Set();
            return 0;
        }
    }

    [DllImport("Mmdevapi.dll", ExactSpelling = true)]
    static extern int ActivateAudioInterfaceAsync([MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath,
        ref Guid riid, IntPtr activationParams, IActivateAudioInterfaceCompletionHandler completionHandler,
        out IActivateAudioInterfaceAsyncOperation operation);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct ProcessEntry {
        public uint Size;
        public uint Usage;
        public uint ProcessId;
        public IntPtr DefaultHeapId;
        public uint ModuleId;
        public uint Threads;
        public uint ParentProcessId;
        public int PriorityClassBase;
        public uint Flags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string ExeFile;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint processId);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    static extern bool Process32FirstW(IntPtr snapshot, ref ProcessEntry entry);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    static extern bool Process32NextW(IntPtr snapshot, ref ProcessEntry entry);
    [DllImport("kernel32.dll")]
    static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")]
    static extern IntPtr OpenProcess(uint access, bool inherit, uint processId);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    static extern int GetApplicationUserModelId(IntPtr process, ref uint length, StringBuilder id);

    const int OUT_RATE = 16000;
    /** How long the player's own sound and everything are heard side by side before one is chosen. */
    const int DECIDE_MS = 3000;
    const int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    const int AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    const int AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000;
    const int AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = unchecked((int) 0x80000000);

    static void Check(int hr, string what) {
        if (hr < 0) throw new Exception(what + " 0x" + hr.ToString("X8"));
    }

    /** One capture, turned into 16 kHz mono 16-bit samples as they come. */
    class Capture {
        public readonly string Name;
        readonly IAudioClient client;
        readonly IAudioCaptureClient capture;
        readonly int channels, bits;
        readonly bool isFloat;
        readonly double step;      // source frames per output sample
        double sourcePos;          // position of the next output sample, in source frames of 'carry'
        float carry;               // last source sample of the previous packet
        long written;
        public readonly MemoryStream Pending = new MemoryStream();
        public double SumSquares;
        public long Samples;
        public readonly string Format;

        public Capture(string name, IAudioClient client, int rate, int channels, int bits, bool isFloat) {
            Name = name;
            this.client = client;
            this.channels = channels;
            this.bits = bits;
            this.isFloat = isFloat;
            step = (double) rate / OUT_RATE;
            Format = rate + " " + channels + " " + bits + (isFloat ? " float" : " int");
            Guid captureId = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
            object captureObject;
            Check(client.GetService(ref captureId, out captureObject), "capture service");
            capture = (IAudioCaptureClient) captureObject;
        }

        public void Start() {
            Check(client.Start(), "start");
        }

        public void Stop() {
            client.Stop();
        }

        /** Takes what came since the last call into Pending; silence fills what Windows did not send. */
        public void Read(Stopwatch clock) {
            uint packet;
            Check(capture.GetNextPacketSize(out packet), "packet size");
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
                while (sourcePos < frames) {
                    int i = (int) sourcePos;
                    double frac = sourcePos - i;
                    float value = (float) (mono[i] * (1 - frac) + mono[i + 1] * frac);
                    short s = (short) Math.Max(-32768, Math.Min(32767, (int) (value * 32767)));
                    Pending.WriteByte((byte) s);
                    Pending.WriteByte((byte) (s >> 8));
                    SumSquares += (double) s * s;
                    Samples++;
                    written++;
                    sourcePos += step;
                }
                sourcePos -= frames;
                carry = mono[frames];
                Check(capture.GetNextPacketSize(out packet), "packet size");
            }
            // Windows sends nothing while nothing plays; silence keeps the time line straight.
            long due = (long) (clock.Elapsed.TotalSeconds * OUT_RATE) - OUT_RATE / 10;
            if (due > written) {
                int gap = (int) (due - written);
                Pending.Write(new byte[gap * 2], 0, gap * 2);
                Samples += gap;
                written += gap;
            }
        }

        public double Level() {
            return Samples == 0 ? 0 : Math.Sqrt(SumSquares / Samples);
        }

        public void Drain(Stream output) {
            Pending.WriteTo(output);
            Pending.SetLength(0);
        }
    }

    /** Everything the default speakers play. */
    static Capture Everything() {
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
        Check(client.Initialize(0 /* shared */, AUDCLNT_STREAMFLAGS_LOOPBACK, 10000000, 0, format, IntPtr.Zero),
            "initialize");
        return new Capture("all", client, rate, channels, bits, isFloat);
    }

    /** Only what this process and the processes it started play. */
    static Capture OnlyProcess(uint processId, string name) {
        // AUDIOCLIENT_ACTIVATION_PARAMS: process loopback, the process, including its process tree.
        IntPtr parameters = Marshal.AllocHGlobal(12);
        IntPtr variant = Marshal.AllocHGlobal(24);
        IntPtr format = Marshal.AllocHGlobal(18);
        try {
            Marshal.WriteInt32(parameters, 0, 1 /* AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK */);
            Marshal.WriteInt32(parameters, 4, (int) processId);
            Marshal.WriteInt32(parameters, 8, 0 /* PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE */);
            // PROPVARIANT holding a BLOB: vt, three reserved words, then the size and the pointer.
            for (int i = 0; i < 24; i += 4) Marshal.WriteInt32(variant, i, 0);
            Marshal.WriteInt16(variant, 0, 65 /* VT_BLOB */);
            Marshal.WriteInt32(variant, 8, 12);
            Marshal.WriteIntPtr(variant, IntPtr.Size == 8 ? 16 : 12, parameters);
            Guid clientId = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
            var activation = new Activation();
            IActivateAudioInterfaceAsyncOperation operation;
            Check(ActivateAudioInterfaceAsync("VAD\\Process_Loopback", ref clientId, variant, activation, out operation),
                "process loopback");
            if (!activation.Done.WaitOne(5000)) throw new Exception("process loopback timed out");
            Check(activation.Result, "process loopback activation");
            var client = (IAudioClient) activation.Client;
            // Process loopback has no mix format of its own: it converts to the one asked for, 48 kHz 16-bit stereo.
            Marshal.WriteInt16(format, 0, 1 /* PCM */);
            Marshal.WriteInt16(format, 2, 2);
            Marshal.WriteInt32(format, 4, 48000);
            Marshal.WriteInt32(format, 8, 48000 * 4);
            Marshal.WriteInt16(format, 12, 4);
            Marshal.WriteInt16(format, 14, 16);
            Marshal.WriteInt16(format, 16, 0);
            Check(client.Initialize(0, AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK
                | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY, 10000000, 0, format,
                IntPtr.Zero), "initialize process loopback");
            // Event mode is required; the event is not waited for, the packets are fetched like the other capture's.
            var ready = new AutoResetEvent(false);
            Check(client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()), "event");
            readyEvents.Add(ready);
            return new Capture(name + "#" + processId, client, 48000, 2, 16, false);
        } finally {
            Marshal.FreeHGlobal(parameters);
            Marshal.FreeHGlobal(variant);
            Marshal.FreeHGlobal(format);
        }
    }

    static readonly List<AutoResetEvent> readyEvents = new List<AutoResetEvent>();

    /**
     * The process that plays for the media session's app: the app user model ID of a packaged app (the Store's
     * Spotify), else its executable's name ("Spotify.exe", "Chrome", "MSEdge"); the topmost of its processes, whose
     * tree holds the one that sounds. 0 when none is found.
     */
    public static uint FindPlayer(string app, out string name) {
        name = null;
        if (string.IsNullOrEmpty(app) || app == "-") return 0;
        var names = new List<string>();
        string tail = app.Contains("!") ? app.Substring(app.LastIndexOf('!') + 1) : app;
        foreach (string candidate in new string[] { tail, tail.Split('.')[0], app.Split('!')[0].Split('_')[0].Split('.')[0] }) {
            string plain = candidate.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
                ? candidate.Substring(0, candidate.Length - 4) : candidate;
            if (plain.Length > 0 && !names.Contains(plain.ToLowerInvariant())) names.Add(plain.ToLowerInvariant());
        }
        var parent = new Dictionary<uint, uint>();
        var exe = new Dictionary<uint, string>();
        var matching = new List<uint>();
        IntPtr snapshot = CreateToolhelp32Snapshot(2 /* processes */, 0);
        if (snapshot == new IntPtr(-1)) return 0;
        try {
            var entry = new ProcessEntry();
            entry.Size = (uint) Marshal.SizeOf(typeof(ProcessEntry));
            for (bool more = Process32FirstW(snapshot, ref entry); more; more = Process32NextW(snapshot, ref entry)) {
                string file = entry.ExeFile ?? "";
                string plain = (file.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? file.Substring(0, file.Length - 4)
                    : file).ToLowerInvariant();
                parent[entry.ProcessId] = entry.ParentProcessId;
                exe[entry.ProcessId] = file;
                bool match;
                if (app.Contains("!")) {
                    // A packaged app's processes carry its ID; one that cannot be asked counts by its name.
                    string id = ModelId(entry.ProcessId);
                    match = id != null ? app.Equals(id, StringComparison.OrdinalIgnoreCase) : names[0] == plain;
                } else {
                    match = names.Contains(plain);
                }
                if (match) matching.Add(entry.ProcessId);
            }
        } finally {
            CloseHandle(snapshot);
        }
        foreach (uint id in matching) {
            uint up;
            if (!parent.TryGetValue(id, out up) || !matching.Contains(up)) {
                name = exe[id];
                return id;
            }
        }
        return 0;
    }

    /** Text for the header line: no spaces, nothing but plain letters, digits and a few marks. */
    static string Clean(string text) {
        var plain = new StringBuilder();
        foreach (char c in text ?? "") {
            plain.Append(c < 128 && (char.IsLetterOrDigit(c) || ".:#_-".IndexOf(c) >= 0) ? c : '-');
        }
        return plain.ToString();
    }

    static string ModelId(uint processId) {
        IntPtr process = OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, processId);
        if (process == IntPtr.Zero) return null;
        try {
            uint length = 256;
            var id = new StringBuilder((int) length);
            return GetApplicationUserModelId(process, ref length, id) == 0 ? id.ToString() : null;
        } finally {
            CloseHandle(process);
        }
    }

    public static void Run(int parentPid, string app) {
        Exception failure = null;
        // Process loopback is activated from a thread of the multithreaded apartment; so is the rest, to keep it simple.
        var worker = new Thread(delegate() {
            try {
                Record(parentPid, app);
            } catch (Exception e) {
                failure = e;
            }
        });
        worker.SetApartmentState(ApartmentState.MTA);
        worker.Start();
        worker.Join();
        if (failure != null) throw failure;
    }

    static void Record(int parentPid, string app) {
        Capture all = Everything();
        Capture only = null;
        string why = "no-player";
        string name;
        uint player = FindPlayer(app, out name);
        if (player != 0) {
            try {
                only = OnlyProcess(player, Clean(name));
            } catch (Exception e) {
                why = "not-alone:" + Clean(e.Message);
            }
        } else if (app != "-") {
            why = "no-process";
        }
        Process parent = parentPid > 0 ? Process.GetProcessById(parentPid) : null;
        Stream output = Console.OpenStandardOutput();
        var clock = Stopwatch.StartNew();
        all.Start();
        if (only != null) {
            try {
                only.Start();
            } catch (Exception e) {
                why = "not-alone:" + Clean(e.Message);
                only = null;
            }
        }
        Capture chosen = all;
        try {
            if (only != null) {
                // Both side by side for a moment: the player's own sound wins unless it stays silent while the PC
                // plays something, which means its sound comes from a process outside its tree.
                while (only != null && clock.ElapsedMilliseconds < DECIDE_MS && (parent == null || !parent.HasExited)) {
                    all.Read(clock);
                    try {
                        only.Read(clock);
                    } catch (Exception e) {
                        why = "not-alone:" + Clean(e.Message);
                        only.Stop();
                        only = null;
                    }
                    Thread.Sleep(20);
                }
                if (only != null && all.Level() > 300 && only.Level() < all.Level() * 0.05) {
                    why = "silent:" + only.Name;
                    only.Stop();
                    only = null;
                }
                if (only != null) {
                    chosen = only;
                    all.Stop();
                }
            }
            // The header tells how long ago the first sample was taken, so the mod puts the samples on its clock.
            string mode = chosen != all ? "only " + chosen.Name : "all " + why;
            byte[] header = Encoding.ASCII.GetBytes("LOOPBACK " + OUT_RATE + " " + chosen.Format + " "
                + clock.ElapsedMilliseconds + " " + mode + "\n");
            output.Write(header, 0, header.Length);
            chosen.Drain(output);
            output.Flush();
            while (parent == null || !parent.HasExited) {
                chosen.Read(clock);
                chosen.Drain(output);
                output.Flush();
                Thread.Sleep(20);
            }
        } catch (IOException) {
            // The mod closed the pipe: it stopped listening.
        } finally {
            chosen.Stop();
        }
    }
}
'@

[LyricsLoopback]::Run($ParentPid, $App)
