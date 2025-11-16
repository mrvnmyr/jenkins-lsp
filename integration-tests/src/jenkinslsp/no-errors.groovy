String sufficientElseReturn(String s) {
    if (s) {
        return "TRUE"
    } else {
        return "FALSE"
    }
}

String sufficientSwitchReturn(String s) {
    switch (s) {
    case "foo":
        return "foo"
        break;
    default:
        return "bar"
        break;
    }
}
