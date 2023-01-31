const {createServer} = require('http');
const fs = require('fs');

const server = createServer(function (req, res) {
    if (req.url === '/global') {
        const data = fs.readFileSync('./global.json', 'utf8');
        res.writeHead(200, {'Content-Type': 'application/json'});
        res.write(data);
        res.end();
    }
});

server.listen(9010);

