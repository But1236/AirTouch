namespace AirTouch.Core;

/// <summary>
/// Parses what Fleck hands us for the request target. Fleck exposes the raw path (and, depending on
/// the version, the raw path plus query), so we normalise defensively instead of assuming a shape.
/// </summary>
public static class ConnectionRequest
{
    public const string TokenHeaderName = "X-Auth-Token";

    public static bool TryParse(string? rawPath, out string path, out Dictionary<string, string> query)
    {
        path = string.Empty;
        query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        if (string.IsNullOrWhiteSpace(rawPath))
        {
            return false;
        }

        var value = rawPath.Trim();

        // Some servers pass the whole request target or an absolute URI.
        if (Uri.TryCreate(value, UriKind.Absolute, out var absolute))
        {
            path = absolute.AbsolutePath;
            ParseQuery(absolute.Query, query);
            return path.Length > 0;
        }

        var queryIndex = value.IndexOf('?');
        if (queryIndex >= 0)
        {
            path = value[..queryIndex];
            ParseQuery(value[(queryIndex + 1)..], query);
        }
        else
        {
            path = value;
        }

        if (path.Length == 0)
        {
            return false;
        }

        return true;
    }

    /// <summary>Extracts the pairing token from the query string or a header fallback.</summary>
    public static string? GetToken(
        IReadOnlyDictionary<string, string>? query,
        IEnumerable<KeyValuePair<string, string>>? headers)
    {
        if (query is not null &&
            query.TryGetValue("token", out var fromQuery) &&
            !string.IsNullOrWhiteSpace(fromQuery))
        {
            return fromQuery.Trim();
        }

        if (headers is not null)
        {
            foreach (var pair in headers)
            {
                if (string.Equals(pair.Key, TokenHeaderName, StringComparison.OrdinalIgnoreCase) &&
                    !string.IsNullOrWhiteSpace(pair.Value))
                {
                    return pair.Value.Trim();
                }
            }
        }

        return null;
    }

    private static void ParseQuery(string query, Dictionary<string, string> into)
    {
        if (string.IsNullOrWhiteSpace(query))
        {
            return;
        }

        var span = query.StartsWith('?') ? query[1..] : query;

        foreach (var part in span.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var separator = part.IndexOf('=');
            if (separator <= 0)
            {
                continue;
            }

            var key = Uri.UnescapeDataString(part[..separator]);
            var value = Uri.UnescapeDataString(part[(separator + 1)..].Replace('+', ' '));
            into[key] = value;
        }
    }
}
