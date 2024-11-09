![Screenshot of a procedurally generated emerald wall](./src/screenshot.png)

# Instructions

Open the file `sandbox.cljs` in your editor.

Start the server:

```shell
cd src
npm install # you only need to run this once
npm run watch
```

This will print out the IP address the server is running on.

Connect from Minecraft (bedrock edition) in chat on your device (needs to be on the same network):

```
/connect IP-ADDRESS:3000
```

Then edit [`sandbox.cljs`](./sandbox.cljs) to add lines like this:

```clojure
(send-command "setblock ~2 ~2 ~2 emerald_block")
```

Once you save the file it will auto-reload and that will be run and the command will be sent to Minecraft.

# API

Here are all the things you can do in `sandbox.cljs`.

### (send-command "... command e.g. setblock ...")

Sends any command into minecraft, e.g.:

```clojure
(send-command "setblock ~2 ~2 ~2 emerald_block")
```

You can use this to:

* create blocks with the `setblock x y z block_name` command.
* destroy blocks with `setblock x y z minecraft:air destroy`.

You can pass in multiple strings and they will all be concatenated.

### (on event-name callback)

When an event happens like "BlockPlaced" your callback will get called with the payload data as the first argument e.g.:

```clojure
(on "BlockPlaced"
    (fn [payload]
      (js/console.log "Block created:" (aget payload "body" "block" "id"))))
```

### (on-message first-word callback)

When the player types a chat message with the first word matching `first-word` your callback will be called with the chat message parts as the frist argument, and the payload data as the second argument.

For example this will receive any message starting with the word "test" and print the rest of the message:

```clojure
(on-message
  "test"
  (fn [message payload]
    (js/console.log "got" (pr-str message))
    (js/console.log "payload" payload)))
```

You can use this in combination with `send-command` to react to player messages by building things or digging.
