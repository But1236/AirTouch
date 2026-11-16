using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace AirTouch.Core;

/// <summary>A usable local IPv4 endpoint that a phone could reach over the LAN.</summary>
/// <param name="Address">Dotted-quad IPv4 address.</param>
/// <param name="AdapterName">Human readable adapter description, shown in the UI.</param>
/// <param name="HasGateway">Whether the adapter currently has a default gateway.</param>
/// <param name="IsWireless">Whether the adapter looks like Wi-Fi.</param>
public sealed record LocalAddress(string Address, string AdapterName, bool HasGateway, bool IsWireless)
{
    public override string ToString() =>
        HasGateway ? $"{Address}  ({AdapterName})" : $"{Address}  ({AdapterName}, 无网关)";
}

/// <summary>
/// Enumerates the LAN addresses this machine can be reached on, best candidate first.
/// </summary>
public static class LocalAddressProvider
{
    /// <summary>
    /// Returns every up, non-loopback IPv4 unicast address, ordered by "most likely reachable from
    /// the phone": adapters with a default gateway first, then wireless, then everything else.
    /// </summary>
    public static IReadOnlyList<LocalAddress> GetCandidates()
    {
        var results = new List<LocalAddress>();

        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up)
            {
                continue;
            }

            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
            {
                continue;
            }

            IPInterfaceProperties props;
            try
            {
                props = nic.GetIPProperties();
            }
            catch (Exception)
            {
                continue;
            }

            var hasGateway = props.GatewayAddresses
                .Any(g => g.Address is not null && g.Address.AddressFamily == AddressFamily.InterNetwork);

            var isWireless = nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211;

            foreach (var info in props.UnicastAddresses)
            {
                if (info.Address.AddressFamily != AddressFamily.InterNetwork)
                {
                    continue;
                }

                if (IPAddress.IsLoopback(info.Address))
                {
                    continue;
                }

                // 169.254.x.x means "DHCP failed"; such an address cannot reach anything.
                var bytes = info.Address.GetAddressBytes();
                if (bytes[0] == 169 && bytes[1] == 254)
                {
                    continue;
                }

                results.Add(new LocalAddress(info.Address.ToString(), nic.Name, hasGateway, isWireless));
            }
        }

        return results
            .OrderByDescending(a => a.HasGateway)
            .ThenByDescending(a => a.IsWireless)
            .ThenBy(a => a.AdapterName, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    /// <summary>Best single guess for the address to print in the QR code.</summary>
    public static string? GetPreferredAddress() => GetCandidates().FirstOrDefault()?.Address;

    /// <summary>True when <paramref name="address"/> is one of this machine's current IPv4 addresses.</summary>
    public static bool IsLocalAddress(string address)
    {
        if (!IPAddress.TryParse(address, out var parsed))
        {
            return false;
        }

        return GetCandidates().Any(a => a.Address == parsed.ToString());
    }
}
