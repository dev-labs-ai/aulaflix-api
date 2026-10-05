// The key media.aulaflix.com.br counts connections by: the client's IPv4 address, or the /64 of its IPv6 address, which
// one household or one server usually holds whole. nginx loads it with js_import (see aulaflix.conf).

// "203.0.113.7" stays as it is; "2001:DB8:0:1:aaaa::5" becomes "2001:db8:0:1::/64"
function limitKey(address) {
    if (address.indexOf(':') === -1) {
        return address;
    }
    var mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(address);
    if (mapped) {
        return mapped[1];
    }
    // A dotted IPv4 tail fills the last 32 bits, which the /64 never reaches
    var halves = address.toLowerCase().replace(/\d+\.\d+\.\d+\.\d+$/, '0:0').split('::');
    var head = halves[0] ? halves[0].split(':') : [];
    var tail = halves.length > 1 && halves[1] ? halves[1].split(':') : [];
    var groups = head.concat(new Array(8 - head.length - tail.length).fill('0'), tail);
    return groups.slice(0, 4).map(function (group) {
        return parseInt(group, 16).toString(16);
    }).join(':') + '::/64';
}

function clientKey(r) {
    return limitKey(r.remoteAddress);
}

export default { clientKey, limitKey };
