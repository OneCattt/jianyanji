const { installPrivacy } = require("./utils/privacy");

App({
  onLaunch() {
    installPrivacy();
  },
});
