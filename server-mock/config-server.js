var http = require('http');

var server = http.createServer(function (req, res) {

    if (req.url == '/') { //check the URL of the current request
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.write(JSON.stringify({}));
            res.end();
    }
});

server.listen(9010);

