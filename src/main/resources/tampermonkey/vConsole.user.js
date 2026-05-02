// ==UserScript==
// @name         vConsolev
// @namespace    https://viayoo.com/ldqu2n
// @version      0.2
// @description  via vConsole
// @author       You
// @run-at       document-start
// @match        *://*/*
// @grant        none
// ==/UserScript==

(function() {

    'use strict';
    var s = document.createElement('script');
    s.src = 'https://cdnjs.cloudflare.com/ajax/libs/vConsole/3.15.1/vconsole.min.js';
    s.onload = function() {
        new VConsole();
        console.info('vConsole 3.15.1 ready');
    };

    document.head.appendChild(s);
})();
