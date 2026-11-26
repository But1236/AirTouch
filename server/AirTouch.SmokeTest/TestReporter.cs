using System.Text;
using System.Text.Json;

namespace AirTouch.SmokeTest;

public sealed record TestOutcome(string Name, bool Passed, string? Detail);

/// <summary>Tiny assertion collector; no test framework is available offline.</summary>
public sealed class TestReporter
{
    private readonly List<TestOutcome> _results = new();

    public int Passed => _results.Count(r => r.Passed);

    public int Failed => _results.Count(r => !r.Passed);

    public int Skipped { get; private set; }

    public void Check(string name, bool condition, string? detail = null)
    {
        _results.Add(new TestOutcome(name, condition, detail));
        Write(condition ? "PASS" : "FAIL", name, detail);
    }

    public void Equal<T>(string name, T expected, T actual)
    {
        var ok = EqualityComparer<T>.Default.Equals(expected, actual);
        Check(name, ok, ok ? null : $"期望 {Format(expected)}，实际 {Format(actual)}");
    }

    public void Section(string title)
    {
        Console.WriteLine();
        Console.WriteLine($"=== {title} ===");
    }

    public void Info(string message) => Console.WriteLine($"      · {message}");

    public void Skip(string name, string reason)
    {
        Skipped++;
        Console.WriteLine($"SKIP  {name}（{reason}）");
    }

    public int Summarize()
    {
        Console.WriteLine();
        Console.WriteLine(new string('-', 64));
        Console.WriteLine($"结果：通过 {Passed}，失败 {Failed}，跳过 {Skipped}");

        if (Failed == 0)
        {
            Console.WriteLine("全部通过 ✔");
            return 0;
        }

        Console.WriteLine("失败项：");
        foreach (var outcome in _results.Where(r => !r.Passed))
        {
            Console.WriteLine($"  - {outcome.Name}{(outcome.Detail is null ? string.Empty : " :: " + outcome.Detail)}");
        }

        return 1;
    }

    private static void Write(string tag, string name, string? detail)
    {
        Console.WriteLine(detail is null ? $"{tag}  {name}" : $"{tag}  {name} :: {detail}");
    }

    private static string Format<T>(T value) => value switch
    {
        null => "<null>",
        string text => $"\"{text}\"",
        byte[] bytes => $"byte[{bytes.Length}]",
        _ => value.ToString() ?? "<null>",
    };
}

/// <summary>Helpers shared by the test groups.</summary>
public static class TestJson
{
    public static string? GetString(string json, string property)
    {
        using var document = JsonDocument.Parse(json);
        return document.RootElement.TryGetProperty(property, out var element) &&
               element.ValueKind == JsonValueKind.String
            ? element.GetString()
            : null;
    }

    public static long GetLong(string json, string property)
    {
        using var document = JsonDocument.Parse(json);
        return document.RootElement.TryGetProperty(property, out var element) &&
               element.ValueKind == JsonValueKind.Number
            ? element.GetInt64()
            : 0;
    }

    /// <summary>
    /// Reads a boolean field, keeping "explicit false" distinguishable from "absent" — the zoom frames
    /// carry <c>available:false</c>, which must not be confused with a missing field.
    /// </summary>
    public static bool? GetBool(string json, string property)
    {
        using var document = JsonDocument.Parse(json);
        if (!document.RootElement.TryGetProperty(property, out var element))
        {
            return null;
        }

        return element.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null,
        };
    }

    public static string Describe(byte[] payload, int max = 200)
    {
        var text = Encoding.UTF8.GetString(payload);
        return text.Length <= max ? text : text[..max] + "…";
    }
}
